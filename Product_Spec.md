# Product Specification: Universal Android Macro Engine


## 1. The Scripting Engine & Task Manager

# Product Specification — Section 1

## 1. The Scripting Engine & Task Manager

This section defines the **app-layer authoring and orchestration surface**: how a user expresses a multi-step, game-agnostic automation flow as a *script*, how that script is parsed and validated, and how the runtime schedules and executes it. It deliberately abstracts the platform away — a script is pure orchestration. *What* a step does at the OS level is the concern of the actuator layer (§2, out of scope here); this section is concerned only with *what the user wrote, whether it is well-formed, and when it runs*.

The design target is a scripting language that is **simple enough for a non-programmer to read at a glance** yet **expressive enough to encode real game loops** — a fish-until-full-inventory-then-sell-then-repair cycle, a farm-then-craft-then-deposit rotation, a dungeon-clear-and-loot pass — without any per-game knowledge baked into the engine. We achieve this by splitting the problem into two cooperating layers: a **declarative flow model** (the authoring surface) and a **deterministic execution kernel** (the queue and loop drivers).

---

## 1.2 Authoring Model: Dual-Surface, Single Intermediate Representation

Users author flows through **two front-ends** that both compile down to the same **Intermediate Representation (IR)**:

1. **Visual Builder** — a node/step editor for users who never want to see syntax.
2. **Text Editor** — a DSL for power users who want diffable, versionable, copy-pasteable scripts.

Both are **lossless for the shared subset** and **round-trippable** where the visual model can represent the construct. This is the central contract: a script authored by hand and a flow authored visually that express the same program produce **byte-identical IRs**, and therefore execute identically. The text DSL is the canonical *interchange* format; the visual builder is a projection of it. This kills the "visual things and code things behave differently" class of bug at the source.

### 1.2.1 Why a DSL over raw JSON/YAML

We support **all three serializations**, but they are *not* equal:

| Format | Role | Used for |
|--------|------|----------|
| **DSL** (text) | Authoring + diffing | Human writing, version control, sharing |
| **JSON** (IR) | Canonical/compiled | Storage, transport, import/export, the builder's native memory model |
| **YAML** | Human-friendly IR | Read-only review of generated flows; advanced users who prefer it to DSL |

The DSL *parses to* JSON; it is never stored directly. The visual builder reads/writes JSON directly and never sees DSL. The `.macro` file on disk is a small envelope (see §1.2.6) wrapping the JSON IR plus metadata. This means the engine has **exactly one runtime input format** (JSON IR) — the parser is a pure, offline front-end and failures there can never corrupt execution.

### 1.2.2 The DSL (illustrative)

The DSL is line-oriented and readable, with the following shape:

```macro
# inventory_rotation.macro

@meta {
  name: "Inventory Rotation"
  require_caps: [INPUT_INJECT, SCREEN_CAPTURE]
  timeout: 45m
}

on start:
    call ensure_game_foreground

loop "farm until full" every 1200ms, max 200:
    await tap(btn("FISH"))
    await wait_for(img("catch_prompt"), timeout=8s)
    await swipe(path=polyline(400,900 -> 400,300), speed=1800)
    until condition: inv_full()
    else on_timeout: log("catch never appeared")
    else on_timeout: break

    if inv_full():
        break

loop "sell everything" max 3:
    await tap(btn("MENU"))
    await tap(btn("SELL_ALL"))
    await tap(btn("CONFIRM"))
    await wait_until(not inv_full(), timeout=6s)

call return_to_farm

sub ensure_game_foreground:
    if not foreground(pkg("com.example.fish")):
        launch(pkg("com.example.fish"))
    await wait_until(foreground(pkg("com.example.fish")), timeout=10s)
```

Design constraints on this surface:

- **Steps are `await`ed by default.** Every action that produces an effect is implicitly asynchronous; the `await` keyword is mandatory to make suspension visually obvious (see P3 in the reference architecture).
- **No host types appear.** `tap`, `swipe`, `img`, `btn` resolve to named *targets* and *actions* — never to pixels, `MotionEvent`s, or accessibility node IDs. These are **selectors** (see §1.2.4).
- **Control flow is restricted to a small, analysable set**: `if/else`, `loop {…}`, `while`, `for each`, `break`, `continue`, `call`, `return`. There is no general recursion, no dynamic code eval, no arbitrary expressions with side effects. This is what makes static validation and queue pre-compilation possible (§1.4).
- **Expressions are pure.** Conditions (`inv_full()`, `not foreground(...)`) evaluate against a **state snapshot** and are side-effect-free. Separation of *queries* (pure, predicate) from *commands* (effectful, awaited) is enforced by the type checker (§1.3).

### 1.2.3 Grammar & Surface Syntax (EBNF sketch)

```ebnf
flow        = header, { stmt } ;
header      = "@meta" "{" { meta_kv } "}" ;
meta_kv     = ident ":" ( string | number | duration | list ) ;

stmt        = subdef | call_stmt | loop_stmt | if_stmt
            | action_stmt | let_stmt | control_stmt ;

action_stmt = "await" call_expr [ "else" "on_timeout" ":" stmt_block ]
                                  [ "else" "on_fail"   ":" stmt_block ] ;
loop_stmt   = "loop" [ string ] [ "every" duration ] [ "max" int ] ":" block ;
if_stmt     = "if" expr ":" block [ "else" ":" block ] ;
call_stmt   = "call" ident ;
control_stmt= "break" | "continue" | "return" ;

call_expr   = ident "(" [ arglist ] ")" ;
target      = "btn(" string ")" | "img(" string ")" | "coord(" int "," int ")"
            | "text(" string ")" | "region(" string ")" ;
```

The full grammar is fixed at the IR boundary: the parser (`DSL → AST → IR`) is a single pass with no type-directed parsing, so tooling (syntax highlighting, LSP-style hover, the visual builder's importer) can be built incrementally without touching the engine.

### 1.2.4 Selectors — the game-agnostic seam

The engine must be *universal* (P2: capability discovered, never assumed), so **no step names a concrete coordinate or raw pixel match**. Instead, actions reference **named selectors**, declared in an optional `@targets` block, resolved through a **selector registry port**:

```macro
@targets {
  FISH:       img("assets/fish_button.png") @conf 0.85
  SELL_ALL:   text("Sell All") | ocr("Sell All")
  CATCH_PROMPT: img("assets/catch.png")
  INV_SLOT:   region("inventory_grid")
}
```

A selector is a *declaration of intent*, not an implementation. The actuator layer (§2) chooses the resolution strategy (template match, OCR, node lookup) based on available capabilities; the script only names the intent. This is the app-layer manifestation of P2 — the script branches on **selectors and capabilities**, never on device model or API level. When a capability is unavailable, the runtime emits a *resolution fault* (§1.6.3) scoped to that selector, permitting per-step fallbacks (`img(..) | ocr(..)`).

### 1.2.5 The Intermediate Representation (IR)

The IR is **immutable, fully-resolved, and position-annotated**. Every node carries a source span (`file:line:col`) so runtime faults map back to the user's text or builder node.

```json
{
  "ir_version": "1.0",
  "flow_id": "flow_8f3a…",
  "meta": { "name": "Inventory Rotation", "timeout_ms": 2700000,
            "require_caps": ["INPUT_INJECT", "SCREEN_CAPTURE"] },
  "targets": {
    "FISH": { "kind": "image", "asset": "assets/fish_button.png",
              "confidence": 0.85, "span": "L12:12" }
  },
  "body": [
    { "op": "call", "ref": "ensure_game_foreground", "span": "L5:5" },
    { "op": "loop", "label": "farm until full", "every_ms": 1200, "max_iter": 200,
      "body": [
        { "op": "action", "call": "tap", "args": ["btn:FISH"], "span": "L7:10",
          "on_timeout": [ { "op": "log", "msg": "catch never appeared" },
                          { "op": "break" } ] },
        { "op": "action", "call": "wait_for",
          "args": ["CATCH_PROMPT"], "timeout_ms": 8000, "span": "L8:10" },
        { "op": "action", "call": "swipe", "args": [{ "path": "polyline",
          "pts": [[400,900],[400,300]], "speed": 1800 }], "span": "L9:10" }
      ] }
  ]
}
```

The IR is the **unit of everything downstream**: validation, cost estimation, storage, sharing, and execution. The engine never sees the DSL or the builder's in-memory graph — only this.

### 1.2.6 File Envelope

A saved script is a single `.macro` file: a short magic/version header, the JSON IR (or its gzip), and a checksum. This makes a flow a **portable artifact** — importable, shareable, and versionable independent of the app. The on-disk schema is forward-compiled on load: an older `ir_version` is migrated by a registry of pure migration functions before validation; an unknown newer version is rejected with a clear "update required" fault rather than executed optimistically.

---

## 1.3 Static Validation (compile-time)

Before a flow can ever be enqueued, it passes a **pure, no-I/O validation pipeline**. Validation failures are *authoring* errors and are surfaced in the editor, never at runtime.

1. **Parse** — DSL/YAML/JSON → AST. Syntax faults carry a span.
2. **Resolve** — every `call`, `sub`, and selector reference must bind. Unbound `loop` labels, `call`s to undefined subs, and references to undeclared `@targets` are rejected.
3. **Type-check** — commands vs. queries (P2): a predicate cannot appear as a statement; a command cannot appear inside `if`. Arity/type of built-in action args (`tap`, `swipe`, `wait_for`, …) are checked against a fixed signature table.
4. **Control-flow analysis** — build the CFG; reject unreachable statements after `return`/`break` at top of a block; verify `break`/`continue` are inside a loop; verify `every`/`max` are well-formed.
5. **Capability pre-flight** — against the *declared* capability set (`require_caps`) plus the *discovered* set (P2), flag steps whose selector kind has no possible resolver on this device (e.g. `ocr(...)` with no OCR capability). This is a **warning**, not a hard failure, so flows remain portable and validatable on a partial-capability host.
6. **Resource budget** — compute a static upper bound on iterations (`loop max`) and on wall-clock (`meta.timeout`) and warn if absent or exceeding a configured ceiling (§1.5.2).

Validation is **deterministic and offline** — it can run on a CI machine, in the editor, or on import, with identical results. This is only possible because the IR is closed (P1/P3): no validation step requires the platform.

---

## 1.4 Runtime Architecture — The Execution Kernel

The runtime is a **single-threaded cooperative scheduler** (a coroutine-based kernel) driving an **async statement** model. There is exactly one **flow instance** executing at a time within a **run lane**; multiple run lanes may exist (§1.5), but each is independently serialized. This gives **deterministic ordering without a thread per step** — critical for reliable automation.

### 1.4.1 Core objects

| Component | Responsibility |
|-----------|----------------|
| **FlowCompiler** | IR → **executable program**: a flat instruction/op list plus a side table of continuation frames. Pure, cached by `flow_id` + `ir_version`. |
| **RunLane** | Owns a single **Program Counter (PC)** stack, a **scope/register file**, and a **state snapshot** reference. Serialized. |
| **Scheduler** | The event loop. Advances the current PC when the head async op completes, times out, or faults. Never runs two PCs concurrently in one lane. |
| **LoopDriver** | Implements `every`/`max`/`while` semantics (§1.4.4). |
| **Ports** (from P1) | `ActionPort` (inject/observe), `StatePort` (query), `ClockPort`, `LogPort`, `FaultPort`. The kernel only ever calls ports. |

### 1.4.2 The execution model: an explicit program counter over a stack of frames

Rather than recursive interpretation (which makes suspension and resumption awkward), the compiler **flattens control flow** into a sequence of ops with explicit jump targets — a compact bytecode-like IR (not exposed to users):

```text
0   CALL_SUB      ensure_game_foreground     ; push frame
1   LOOP_BEGIN    label=farm  every=1200 max=200
2   ACTION        tap(btn:FISH)   ;on_timeout -> 5
3   ACTION        wait_for(CATCH_PROMPT) timeout=8000 ;on_timeout -> 5
4   ACTION        swipe(polyline…,1800)
5   COND          inv_full() -> 6 else 7
6   BREAK_LOOP
7   LOOP_END
…
```

The kernel holds `{ lane, pc, frames: [Frame], pendingOp: Option<Op> }`. Each scheduler tick does exactly one of:

- **If `pendingOp` is None**: fetch op at `pc`, advance PC, execute it. Pure ops (condition eval, `let`, `break`/`continue`) complete synchronously. Effectful ops (`ACTION`, `wait_for`, `call`) create a **continuation** and register a completion token with the relevant port; `pendingOp` becomes `Some(token)`.
- **If `pendingOp` is Some**: the scheduler polls/handles the port callback (completed / failed / timed-out), maps it to the op's `on_*` branch target (or PC+1 on success), clears `pendingOp`, resumes.

Because every action is a suspension point, the **`await` distinction is structural**: an `ACTION` is a suspend op; a `COND` is a sync op. The user cannot accidentally write a non-suspending action, because the compiler emits only suspend or sync opcodes per the signature table.

### 1.4.3 Sub-calls without stack explosion

`call` pushes a `Frame { return_pc, locals }` onto the lane's frame stack and jumps to the sub's entry. Recursion is **statically forbidden** (§1.3.4), so depth is bounded by the declared sub graph — no stack overflow under a malicious script. `return` pops the frame and resumes `return_pc`.

### 1.4.4 Loop drivers

Two loop forms and their exact semantics:

**`loop … every D, max N:`** — an *iteration-bounded, period-driven* loop.

- The **iteration counter** is checked against `max` at `LOOP_BEGIN` and after each body completes. Exceeding → exit.
- `every D` means the **next iteration is scheduled no earlier than `now + D`** after the *previous iteration's body fully completed* (not a fixed wall-clock cadence — a slow action does not compress the next iteration; a fast one does not busy-spin). Concretely, `LOOP_END` computes `next_at = max(now, last_start + D)` via `ClockPort` and parks the lane until then.
- `every 0` (or omitted) yields a tight loop back-to-back with only a yield-to-scheduler between iterations (still cooperative — never blocks the UI thread).

**`while <cond>:`** — predicate-driven; the condition is re-evaluated at `LOOP_BEGIN` each iteration. The `cond` is a pure query over the state snapshot; if the snapshot is stale (§1.6.4) the scheduler refreshes it before evaluating.

**`for each <selector>:`** — enumeration-driven over resolved targets (e.g. inventory slots); the target list is snapshotted once at entry to avoid mutation-during-iteration (a classic game-UI hazard).

**`until <cond>` at loop tail** — syntactic sugar compiled to `if cond: break`, preserving a clean "do until" idiom without new kernel ops.

`break`/`continue` are compile-time jumps to the nearest enclosing loop's exit/continue target. Because these are *static*, a loop can always be suspended, stored, and resumed at a single PC — no hidden interpreter state.

### 


## 2. Low-Latency Detection Engine

# 2. Low-Latency Detection Engine

The Detection Engine is the app-layer subsystem that answers one question per statement: *"does condition C hold on the current frame, right now?"* It must do so in **≤ 8 ms p50 / ≤ 16 ms p99** on a mid-range device (Snapdragon 6-series, 2020 era) while the game itself is running at 60–120 fps — meaning the engine is a **guest** on the CPU and GPU, not a resident. The detection pipeline is therefore designed around three constraints that govern every decision below:

1. **The frame is always fresher than the answer.** Detection never blocks the input dispatch loop; a stale result is preferred over a late one.
2. **Allocation during steady-state detection is zero.** Every hot-path buffer, `Mat`, `Point`, and result slot is pre-allocated at statement-compile time. The GC must never be invoked by the detection engine mid-run.
3. **The expensive operation is not detection — it's *getting pixels*.** The engine consumes an already-decoded frame surface from the Capture Adapter (out of scope here); its job is to extract meaning from it with the fewest possible CPU cycles and zero copies across the JNI boundary.

The pipeline is a compile-then-execute model. Statements are compiled once per flow into an `IDetector` graph; the graph is then executed against a `RingBuffer<FrameHandle>` per tick.

---

## 2.1 Detector Taxonomy & Compiled Form

Three detector families share one lifecycle. The core defines a single port:

```kotlin
interface IDetector {
    val kind: DetectorKind                 // COLOR | TEXT | IMAGE
    val requiresRegion: Rect?              // sub-rect of source frame, or null = full
    fun warmup(allocator: FramePool)
    fun detect(frame: FrameHandle, out: DetectorResult): DetectorResult  // no alloc, no throw
    fun release()
}
```

`detect` is a **pure, non-throwing, allocation-free** function that writes into a caller-supplied `DetectorResult`. This uniformity lets the scheduler treat all three families identically, batch them across regions, and cache results keyed by `(frameId, detectorId)`.

| Family | Micro-seconds (mid-range) | Cost driver | Primary optimization |
|---|---|---|---|
| **COLOR** | 30–200 µs | ROI pixel count | Scalar fast-path / SIMD via OpenCV |
| **TEXT (OCR)** | 3–15 ms | Contours + `TextRecognizer` | Downscale + ROI + tiled model |
| **IMAGE** | 0.5–4 ms | Template area × pyramid levels | `matchTemplate` + ROI + coarsening |

The cost ordering is why the compiler orders detectors **ascending by expected cost** and supports short-circuit (`AND` of `COLOR` before `TEXT`). A flow that gates an OCR read behind a color check on a health bar pays OCR cost only when the bar is visible.

---

## 2.2 Frame Intake & Zero-Copy Contract

The Capture Adapter hands the Detection Engine a `FrameHandle`:

```kotlin
class FrameHandle(
    val hardwareBuffer: HardwareBuffer?,  // preferred path, if Capability.HW_BUFFER_INPUT
    val bitmap: Bitmap?,                  // fallback
    val width: Int, val height: Int,
    val densityScale: Float,              // frame px → app logical px
    val frameId: Long,                    // monotonic, from capture clock
    var inUse: Boolean = false
)
```

Two invariants:

- **The frame is read-only and shared.** It lives in a fixed-size `RingBuffer<FrameHandle>` sized to *capture latency × fps* (typically 3 slots). The capture side only writes to a slot whose `inUse == false`; the detection side pins a slot for the duration of a tick. No `Bitmap.copy()`, no `-DCMAKE`-level pixel marshal on the hot path.
- **Pixels never cross the JNI boundary as `ByteArray`.** All detectors operate on an `org.opencv.core.Mat` that is *wrapped around* the hardware/bitmap memory via `Mat.fromNativeObjAddr` or `nMat` constructed from a `Bitmap` using `Utils.bitmapToMat` **only once per ring slot** (see §2.3). After that, the Mat is reused and re-wrapped by updating its `data` pointer to the slot's `ByteBuffer` base address — a single `putAddress` call per frame, not a memcpy.

### 2.2.1 Direct ByteBuffer fast path

When `Capability.HW_BUFFER_INPUT` is available, the adapter exposes the frame as a **direct, native-order `ByteBuffer`** aligned to the frame stride. The engine maintains, per ring slot, a pre-built `Mat` whose header points at that buffer:

```java
// JNI, once per slot at warmup:
JNIEXPORT void JNICALL
Java_com_engine_detect_NativeMat_bindSlot(JNIEnv* env, jclass, jlong matAddr,
                                          jobject directBuf, jint w, jint h, jint stride) {
    auto* mat = reinterpret_cast<cv::Mat*>(matAddr);
    void* base = env->GetDirectBufferAddress(directBuf);
    *mat = cv::Mat(h, w, CV_8UC4, base, stride);   // header rewrite, no copy
}
```

Per tick the Java side does not touch pixel data at all: it just references a `SlotView` whose `nativeMatAddr` is already valid. This eliminates the single largest source of latency in naive Android CV code — the `bitmap → ByteArray → Mat` round-trip costs 6–20 ms per 1080p frame on a mid-range CPU and is entirely removed here.

### 2.2.2 Fallback path

When no hardware buffer is available (older API levels / emulator), the adapter renders to a **reusable `Bitmap` with `Bitmap.Config.HARDWARE` disabled** and an explicit `setHasAlpha(false)`, allocated once per slot via a `BitmapPool` (Apache Commons-Pool2 or a hand-rolled `SparseArray<ArrayDeque<Bitmap>>` keyed by dimensions). `Utils.bitmapToMat` is called **once at warmup** and the resulting Mat is retained per slot; the adapter copies into the bitmap via `copyPixelsToBuffer`, which is a straight `memcpy` into the slot's `DirectByteBuffer`. Fallback cost: ~1–3 ms/frame at 1080p, acceptable because branch is only compiled in when the capability flag is absent (P2: capability, not version).

---

## 2.3 Region-of-Interest (ROI) Discipline

Full-frame detection is the default mistake. The engine's compiler enforces **ROI-first**: every detector declares a `requiresRegion`, and the scheduler intersects overlapping ROIs across detectors in the same tick into a **minimal union rect**. The frame is then cropped to that rect via Mat header arithmetic (no copy), reducing the pixel budget by 10–100× for HUD-driven macros (health bars, ammo counters, minimap dots).

```
Full frame: 1920×1080 = 2.07 Mpx
Union ROI (health bar + ammo + skill icon): 140×60 + 96×24 + 64×64 ≈ 13 kpx  → 0.6% of frame
```

ROI rects are expressed in **logical app coordinates** and scaled to frame coordinates at compile time using `densityScale`, so a flow authored against a 1080p reference runs correctly against a 1440p frame without per-statement edits. The ROI must be **pixel-aligned to the Mat's CV type** (8-4-4-4 is 4-byte aligned); the compiler asserts this and pads the rect to the nearest multiple of 4 horizontally.

---

## 2.4 Color Detection

Color detection is the highest-frequency detector (every tick on the biggest flows) and therefore the most aggressively specialized. Three implementations are compiled, selected by a capability probe of the CPU (ARMv8 NEON / ARMv7 NEON / scalar) performed once at `warmup`.

### 2.4.1 Semantics

A `ColorStatement` matches a pixel in ROI if `HSV(h) ∈ [h0,h1] ∧ S ∈ [s0,s1] ∧ V ∈ [v0,v1] ∧ RGB-dist(pixel, target) ≤ tol`. The engine precomputes **per-channel lookup tables** so HSV extraction per pixel is two table reads (RGB→HSV is a 256³ problem; we collapse it to a 65 536-entry LUT indexed by `(R>>4)<<8 | (G>>4)<<4 | (B>>4)` with 4-bit precision, then refine only near the boundary). This is 2–3× faster than `cvtColor(RGB→HSV)` on small ROIs because the OpenCV call pays a full-image pass and per-pixel round-trip even for a 140×60 rect.

### 2.4.2 Matching modes

- **ANY / COUNT** — early-exit as soon as the first matching pixel is found (ANY) or after the count threshold is reached (COUNT). Implemented in JNI with a scalar loop + NEON `vld1q_u32` over 4 pixels/iteration for the LUT gather; early exit makes the common "health bar is red" case O(1) on a hit.
- **COVERAGE** — fraction of ROI pixels matching, computed in one SIMD pass with no early exit; compared against a pre-quantized threshold.
- **TEMPLATE-COLOR** — multi-color mask (e.g., a gradient bar), evaluated as a packed 32-bit `AND/OR` over channel masks, no branches.

### 2.4.3 Padding & sampling

For low-frequency ticks the scheduler may **subsample** the ROI on a stride grid (every 2nd or 4th pixel on both axes) — set per-statement by `stride`, default 1. A health bar never needs 4-pixel precision; a stride-4 pass at 9984 px runs in ~30 µs. The stride grid is aligned to the Mat's row stride so the JNI loop is a clean double-nested `for` with no ring-buffer wraparound.

---

## 2.5 Text Detection (OCR)

OCR is 2–3 orders of magnitude more expensive than color and must be *conditionally* engaged. The engine wraps **ML Kit Text Recognition** (preferred, bundled via Play Services or a bundled model) and optionally **Tesseract** for offline/no-GMS builds, behind a `IOcrBackend` port.

### 2.5.1 Latency budget

| Stage | Mid-range | Notes |
|---|---|---|
| ROI crop (header) | ~0 µs | Mat header |
| Preprocess (grayscale, NORM_HAMMING-friendly binarize) | 0.4–1.2 ms | OpenCV `cvtColor`+`threshold` on ROI |
| Downscale (optional) | 0.2–0.6 ms | `INTER_AREA` to ≤ 640px wide |
| Recognizer inference | 3–12 ms | ML Kit; first call is 30–80 ms (model load) |
| Post-filter (regex/conf) | < 0.1 ms | Kotlin, no alloc |

The **first-call cost is moved off the hot path** by `warmup`: the engine feeds a synthetic white-on-black "WARMUP" bitmap through the recognizer during flow compile, so model load, TFLite delegate init, and JIT warmup are paid once, before the first tick.

### 2.5.2 Android-specific optimizations

- **Bundled model, no dynamic download.** ML Kit's *bundled* variant (`com.google.mlkit:text-recognition:16.x`) ships the model in the APK, eliminating the first-run Play Services download and the 200–400 ms cold path that a runtime-downloaded model causes. The tradeoff is APK size; the engine exposes `DetectorConfig.ocrBundled` so a flow can opt into the smaller but slower `play-services-mlkit` variant.
- **TFLite hardware delegate, chosen by probe.** On `warmup` the engine tries, in order: **NNAPI delegate** → **GPU delegate (OpenCL/Vulkan backend)** → **XNNPACK CPU**. The probe runs a 1-inference micro-benchmark per delegate with a hard 150 ms timeout and caches the winner in `SharedPreferences` keyed by device fingerprint. NNAPI is preferred on Pixel/Samsung flagship SoCs (Qualcomm HTP/DSP); it is frequently *slower* on older Mali GPUs, which is why the probe is mandatory and never version-inferred (P2).
- **Input pre-quantization.** The bundled ML Kit model is quantized; feeding it a `Bitmap` forces an internal `bitmap → float tensor` conversion. The engine instead feeds a **pre-allocated `ByteBuffer` of `CV_8UC1`** matching the model's input shape, obtained directly from the downscaled Mat (`mat.data`), skipping the conversion by calling `InputImage.fromByteBuffer` with `ROTATION_0` and the correct row stride. This saves 0.5–2 ms per call.
- **Tiled recognition for long HUDs.** A 1920×1080 HUD line downscaled to 640 px loses 12 px glyphs. The engine detects `<text>` statements whose ROI aspect ratio exceeds 8:1 and **splits into tiles of ≤ 256 px** (aligned to word gaps found by a cheap `findContours` on the binarized mask), recognizing tiles in **parallel across a fixed 2-thread pool** and merging results by tile index. Effective latency falls from ~14 ms to ~6 ms because inference is bounded by the pool, not by a single mega-image.
- **Negative cache.** A text detector's expected regex is compiled at warmup; the engine caches the **last recognized string per statement** and short-circuits the full recognizer when the binarized ROI's **pixel hash** is unchanged from the previous tick (HUD text is frequently static). Hash cost ~5 µs versus ~8 ms recognition — a 1000× win on idle HUDs.
- **Confidence floor & regex pre-filter.** ML Kit returns per-block confidence. Statements with a strict regex (`^\d{2}%$`) are validated *before* the string is materialized as a Kotlin `Any` — the post-filter runs on the raw UTF-16 `Text` block and only allocates a `String` on match.

### 2.5.3 Backpressure

OCR detectors are marked `IDetector.costClass = HEAVY`. The scheduler will not run more than **one HEAVY detector per tick** and may **skip** a HEAVY detector if the previous result is within its `maxStaleness` (default 3 frames). This is the mechanism that keeps OCR off the detection critical path: a macro that reads an ammo counter every tick still executes at 60 fps because the OCR result is reused for up to 3 frames.

---

## 2.6 Image Detection (Template Matching)

Image detection uses `Imgproc.matchTemplate` with **`TM_CCOEFF_NORMED`** (default) or `TM_SQDIFF_NORMED` for binary/UI-chrome templates, over a pre-aligned ROI.

### 2.6.1 Pyramid strategy

The template is preprocessed at warmup into a **Gaussian pyramid** (typically 3 levels: full, ½, ¼), each level stored as a pre-allocated `Mat` in the `StatementCache`. At detect time:

1. **Coarse pass** — `matchTemplate` at level ¼ (or ⅛ for large source ROIs). The template at ¼ is a 16×16-ish Mat; the source ROI is 4× smaller. Cost: ~0.1–0.4 ms.
2. **Peak-and-verify** — take the top-K (`K=3`) peaks above a coarse threshold; for each, **upscale the match location** to full resolution ± dilation radius and run a **localized `matchTemplate`** on a full-res source sub-rect only as large as the (scaled) template + 8 px. Cost: ~0.1 ms per candidate.
3. **Reject & refine** — a candidate is accepted only if the full-res correlation exceeds `threshold`; sub-pixel refinement via `minMaxLoc` + parabolic fit is deferred to result materialization, which only runs on accept.

This strategy reduces cost from `O(W·H·w·h)` (full-res NCC across the whole ROI) to ~`O(W·H·w·h/16) + O(K·w·h)`, a 10–16× win on large ROIs.

### 2.6.2 OpenCV `matchTemplate` tuning

- Use a **`Mat` of `CV_32FC1`** for template and source at the coarse level (conversion is folded into warmup); `TM_CCOEFF_NORMED` pays a `sqrt` per pixel — on ARMv8 NEON, `vrsqrteq_f32` provides a reciprocal-sqrt approximation accurate to ~1e-4, well inside template-matching tolerance, and is 4× faster than `vsqrtq_f32`. The engine's JNI `matchTemplate` fork enables it under `-DCV_USE_NEON` (OpenCV golden build config exposed as a Makefile flag, not a runtime option).
- **Masked matching** is supported via `matchTemplate(..., mask)` for templates with transparent/anti-aliased edges (skill icons). The mask is pre-multiplied at warmup.
- **Reuse of the DFT plan.** For `TM_CCOEFF_NORMED` on large templates OpenCV may choose the FFT path; the engine pins `cv::setUseOptimized(true)` and, for templates larger than 64×64, pre-com


## 3. UI/UX, Overlay, and Local-Only Architecture

# 3. UI/UX, Overlay, and Local-Only Architecture

This section specifies the **app-layer** product surface: how the user authors, launches, monitors, and manages automation flows; how the floating overlay behaves; and how configuration is persisted. It deliberately stops at the boundary of the app layer and hands off to the adapter layer for anything that touches platform primitives.

## 3.1 Local-Only, Account-Free Design (Non-Negotiable)

The product is **strictly local**. This is a product guarantee, not an implementation detail.

- **No login.** There is no sign-in screen, no onboarding gate, no "continue as guest" affordance. The app is fully usable on first launch, offline, on a device that has never touched a network.
- **No account system.** There are no user IDs, no identity, no profiles bound to an email, phone number, or third-party provider. Presence of a `userId` anywhere in the data model is a defect.
- **No cloud sync.** There is no remote backend, no sync service, no device-to-device transfer over the network. Multi-device transfer, if offered at all, is a **local** export/import file (`§3.5`).
- **No telemetry by default.** Any crash reporting or analytics is **opt-in**, off on first launch, and must function identically to the app's core when disabled. The engine must never degrade or block on a network call.
- **No network permission requirement for core function.** The `INTERNET` permission is at most used by an explicit, disabled-by-default opt-in module. Core authoring, overlay control, and execution require **zero** network access.

All configuration, flows, logs, and assets are stored on the device in app-private storage (`§3.6`). The privacy posture is stated verbatim in the app's first-run notice and in the settings "Data & Privacy" panel.

## 3.2 Information Architecture

The app is a single-activity, multi-screen host with a persistent **Macro List** as the home surface. Navigation is shallow (max depth 2) because the primary use case is launching a macro and returning to a game quickly.

```text
Root
├── Macro List (home)
│   ├── Macro Editor (flow authoring)
│   │   ├── Step Picker (palette of step types)
│   │   ├── Step Config (per-step parameters)
│   │   └── Variable / Expression Editor
│   ├── Run Configuration (per-macro options)
│   └── Run History / Logs (per-macro)
├── Settings
│   ├── Overlay & Controls
│   ├── Data & Privacy
│   ├── Import / Export
│   └── Diagnostics
└── First-Run Notice / Disclosures
```

Screen inventory and purpose:

| Screen | Purpose | Persisted state |
|---|---|---|
| **Macro List** | Browse, search, sort, launch, duplicate, delete macros. Shows last-run status badge. | List ordering, search query, filters |
| **Macro Editor** | Ordered step list; drag-reorder; insert/edit/delete; inline validation. | Draft autosaved per macro |
| **Step Picker** | Categorized palette of available step types (tap, swipe, wait, condition, loop, shell, variable op). | Recently-used section |
| **Step Config** | Parameter form for the selected step type; typed editors (point picker, duration, expression). | Bound to parent step |
| **Variable / Expression Editor** | Define run-scoped variables and expressions with live validation. | Per-macro |
| **Run Configuration** | Repetition count, speed, failure policy, pre/post hooks. | Per-macro |
| **Run History** | Chronological run records with step-level outcome and captured diagnostics. | Ring buffer |
| **Settings** | Overlay behavior, data/privacy, import/export, diagnostics toggles. | App-scoped |

The **Macro Editor** is the load-bearing screen. Its UX contract:

- Steps are a **vertical ordered list** with stable drag handles; reorder is a single drag gesture with no modal.
- Every step row shows: type icon, human summary line (e.g. "Tap (120, 480)"), and a validity chip (valid / warning / error).
- Inline validation surfaces errors **before** save; a macro with errors cannot be launched (launch is disabled, tooltip explains).
- The list is **virtualized**; macros with hundreds of steps must scroll at target frame rate.
- Point coordinates are set via a **point-picker** that launches directly into the overlay (see `§3.3`) to sample a location, then returns to the editor with the coordinate filled.

## 3.3 Floating Overlay Menu

The overlay is the runtime control surface, presented **on top of the target app** while a macro runs or is paused.

### 3.3.1 Overlay states

The overlay is a small state machine, not a free-floating set of widgets:

```text
IDLE ──launch──► ARMED ──start──► RUNNING ──pause──► PAUSED
  ▲                 │                 │              │
  │                 │                 └──stop────────┤
  └─────────────────┴───────stop────────────────────┘
                    │
                    └──cancel──► IDLE
```

| State | Visual | Primary action available |
|---|---|---|
| **IDLE** | Collapsed pill / bubble | Open menu; launch selected macro |
| **ARMED** | Expanded menu before start | Start, settings, cancel |
| **RUNNING** | Compact pill with progress + step index; drag to reposition | Pause, stop |
| **PAUSED** | Pill turns amber; shows last completed step | Resume, step once, stop |

### 3.3.2 Overlay behavior contract

- **Draggable & snap-to-edge.** The pill can be dragged anywhere; on release it snaps to the nearest edge with a configurable inset, and the position persists across launches per-macro (`§3.6`).
- **Non-interactive transparent regions.** Only the pill and expanded menu accept touch; the remainder of the overlay surface is a pass-through so the game underneath remains playable. Touch interception is limited to the control footprint — a full-screen blocker is a defect.
- **Collapsed by default during RUNNING.** The expanded menu auto-collapses after a configurable timeout once a run starts, to minimize obstruction; tapping the pill re-expands.
- **Always-available stop.** The stop control is reachable in one tap from every state. There is no confirmation dialog for stop; a long-press can be configured as a "stop and record failure" variant.
- **Progress model.** The pill renders: step `i / n`, current macro name (truncated), elapsed time, and a state color (blue = running, amber = paused, red = error). It never blocks the underlying app's own gestures.
- **Accessibility.** All overlay controls expose content descriptions; the pill supports a screen-reader label reflecting current state and step index.
- **Failure surfacing.** On a fatal step error the overlay switches to an **error pill** (red) with a one-tap "View log" that opens Run History directly, returning the user to the game afterward.

The overlay **is a pure controller**: it emits user intents (start/pause/stop/sample-point) and renders engine-reported state. It does not own execution logic. The engine's async execution state (`§` in the engine reference) is the single source of truth the overlay reflects.

### 3.3.3 Point-picker mode

When launched from the editor, the overlay enters an **inspection mode**: the pill is replaced by a crosshair affordance, a tap samples device-space coordinates, and the editor receives the sampled point. The game's own touches are suppressed only during this one-shot sampling gesture. The picker can be canceled and returns the editor unchanged.

## 3.4 Run UX and Feedback

- **Launch path.** From Macro List, one tap launches the macro and minimizes the app into the overlay (if overlay is enabled) or runs headless with a notification-embedded controls strip (if overlay is disabled in settings).
- **Pre-flight check.** Before start, the app validates the flow (unresolved variables, invalid coordinates, missing capabilities per the `PlatformCapabilities` port) and blocks start with a specific, actionable error.
- **Run History.** Each run records: macro id, start/end timestamps, outcome (completed / stopped / failed), failing step index (if any), and a captured diagnostic text log (bounded ring buffer). Records are local-only and purge on a configurable retention policy (default 50 runs).
- **Toasts vs. banners.** Transient confirmations use inline banners in the editor; persistent state lives in the overlay. No modal blocking of the game during a run.

## 3.5 Import / Export (Local File Transfer)

Multi-device or backup transfer is achieved **without any server**:

- Export produces a single portable **`.mke` bundle** (macro definition + variables + assets + manifest with schema version) written to app-private storage and shared via the OS share sheet or saved to user storage.
- Import validates the bundle's **schema version** and integrity before committing; unknown/newer versions fail closed with a clear message rather than partially importing.
- No identifiers in the bundle reference an account, device, or cloud origin. Two devices exchange files via the user's chosen transfer mechanism; the app never orchestrates it.

## 3.6 Configuration & Persistence Model

All persistence is local, versioned, and migration-aware. The storage layer is abstracted behind a repository interface so the format can evolve without touching UI code.

### 3.6.1 Storage tiers

| Tier | Contents | Durability |
|---|---|---|
| **App-scoped settings** | Overlay enable/position defaults, theme, retention policy, telemetry opt-in, default speed | Survives uninstall only via export |
| **Per-macro definition** | Ordered steps, variables, run configuration, overlay pill position | Persistent |
| **Per-macro draft** | Unsaved editor buffer, autosaved | Replaces definition only on explicit save |
| **Run history** | Bounded ring buffer of run records + logs | Purged by retention policy |
| **Asset store** | Point-picker samples, small user-provided files | Referenced by key from steps |

### 3.6.2 Persistence contract

- **Schema versioning.** Every persisted entity carries a `schemaVersion`. A migration chain upgrades old records on read; a record with a *newer* version than the app supports is refused, not silently coerced.
- **Atomic writes.** Definitions are written to a temp location and swapped in, so a crash mid-save never corrupts a macro. The editor always reads a consistent snapshot.
- **Autosave.** Draft autosave runs on a debounce (target ~1 s idle) and on backgrounding; autosave never overwrites the saved definition.
- **Ordering is authoritative.** Step order is stored explicitly (stable ordering key), not implicitly by insertion order, so reorder and concurrent edits round-trip deterministically.
- **No cross-device identity.** Persisted records carry **no** account, device, or sync metadata. A record exported and imported elsewhere is identical in kind to one created locally.
- **Deletion is complete.** Deleting a macro removes its definition, drafts, history, and orphaned assets in one transaction; nothing is retained off-device because nothing ever leaves the device.

### 3.6.3 Settings surface (app-scoped)

- **Overlay & Controls:** enable/disable overlay, default pill position and edge inset, auto-collapse timeout, long-press-stop behavior.
- **Data & Privacy:** retention policy, telemetry/opt-in status (off by default), "Export all data", "Delete all data" (full local wipe with typed confirmation).
- **Import / Export:** bundle import, per-macro and bulk export.
- **Diagnostics:** verbosity level, log sharing via local file, a redacted capability summary (which capability flags the device reported) for troubleshooting without any network call.

## 3.7 UX Invariants

The following are treated as acceptance criteria for the app layer:

1. **Zero-network first launch.** From install to running a first macro, the app must never require or attempt a network connection.
2. **One-tap stop at all times.** No overlay state hides the stop control behind a modal or long press by default.
3. **No identity.** No screen, log, bundle, or record exposes an account or user identifier.
4. **Fail-closed imports and migrations.** Malformed or newer-versioned data is rejected with a specific error, never partially applied.
5. **Overlay is a controller.** Overlay UI reflects engine-reported state and emits intents; it owns no execution state.
6. **Atomic, versioned persistence.** Every write is crash-safe and every read is migration-aware.
7. **Pass-through play.** The overlay never captures touches outside its own control footprint.

---

*End of Section 3. Section 4 (Step Model & Execution Semantics) and Section 5 (Capability Matrix & Adapters) follow per the reference architecture; behavior described here consumes those contracts through the `PlatformCapabilities` and execution-state ports and does not redefine them.*

