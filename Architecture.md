# Macro Automation Engine — Reference Architecture


## 1. High-Level Architecture (Design Philosophy, Layered Diagram, Ports & Adapters)

# 1. High-Level Architecture

The macro engine is a **layered, dependency-inverted system** whose purpose is to translate user-authored automation flows into platform effects (synthetic input, UI observation, shell commands) while remaining testable, portable, and resilient to the capability differences between host operating systems and API levels.

This section establishes the non-negotiable design rules that govern every subsequent module, then presents the runtime as a layered stack, and finally formalizes the boundary between layers using the **Ports & Adapters** (hexagonal) pattern. Concrete implementation details of individual subsystems are deferred to their respective sections; here we define *where* those subsystems live and *how* they are permitted to talk to one another.

---

## 1.1 Design Philosophy

The architecture is built on seven principles. They are listed in priority order — when two principles conflict, the higher one wins.

### P1. Core is platform-agnostic

The **Domain Core** (flow model, scheduling semantics, expression evaluation, gesture synthesis geometry, result reporting) contains **zero** imports of host-framework types. It never references an `AccessibilityService`, a `MotionEvent`, a `pyautogui` call, or a Windows `SendInput` struct. Platform capabilities are expressed as **interfaces** (ports) that the core defines and the host layer implements.

> **Consequence:** gesture *geometry* (path construction, duration computation from path length and speed) belongs to the core. Gesture *injection* (which Android API, which OS call) belongs to an adapter. This mirrors the source material, where `B(Path, density, speed)` — pure math — is separable from `dispatchGesture()` — pure platform.

### P2. Capability is discovered, never assumed

The engine must run across a wide capability matrix: API 17 vs. 24 vs. 30+, touch-injection available vs. privileged fallback, screen-capture available vs. absent. Every such difference is modeled as an explicit **capability flag** exposed through a `PlatformCapabilities` port, and the core branches on the *flag*, not on a version integer.

```text
BAD:   if (Build.VERSION.SDK_INT >= 24) { native.inject(...) } else { legacy.inject(...) }
GOOD:  if (caps.has(Capability.NATIVE_GESTURE_DISPATCH)) { injector.inject(...) }
                                                     else { injector.injectLegacy(...) }
```

The adapter is the only place that translates `SDK_INT` into a capability flag. Downstream code (including legacy fallbacks) remains unaware of the version number.

### P3. Asynchrony is first-class

Almost every actuator is asynchronous: gesture dispatch completes via a callback (`onCompleted`/`onCancelled`), shell commands return later, screen capture is deferred. The engine's execution unit is therefore an **async statement** whose lifecycle is *started → pending → resolved(success|failure)* — never a blocking call. Completion is reported through a single **Result Channel**, and the scheduler is responsible for resuming the flow.

### P4. Configuration is late-bound and versioned

User flows are persisted. Every persisted field is a **lazily evaluated expression handle** — evaluated against the current execution context at run time, not at load time. This permits flows to reference variables, cross-flow parameters, and computed values. Serialization is **version-tagged**, and readers must be backward-compatible: a field introduced in config version `N` is only read when the stored version `>= N`. Unknown future fields are skipped, not rejected.

### P5. Every actuator declares its own permissions

An actuator is not allowed to assume it has been granted authority. Each statement declares a required-permission set through a `M0(Context)`-style hook, which the host layer maps to a platform permission model. On modern Android this is the standard `AccessibilityService` grant; on legacy hosts it may be a privileged signature permission. The **core never inspects permissions** — it only forwards the declaration to a `PermissionResolver` port.

### P6. Fail loudly, fail closed

Missing required arguments raise a typed exception (`RequiredArgumentNullException`-equivalent) at evaluation time. An invalid speed (path length yields a non-positive duration) is a hard error. The actuator does **not** silently degrade to a no-op; it reports a failed resolution through the Result Channel, and the scheduler decides flow-level policy. Because the engine can synthesize input, silent failure is a correctness and safety hazard.

### P7. Output and input are separate concerns

The engine distinguishes **actuators** (they emit effects: taps, swipes, key events, shell) from **sensors** (they observe state: screen contents, UI tree, foreground app). A single statement is one or the other, modeled as distinct ports (`InputPort`, `ObservationPort`). This prevents the anti-pattern of a gesture statement secretly performing screen capture, and it lets the capability matrix disable sensors (e.g., no capture on a headless host) without touching actuators.

---

## 1.2 Layered Diagram

The runtime is organized into **four layers**, plus a cross-cutting **Capability** concern and an explicit **Persistence** boundary.

```text
┌──────────────────────────────────────────────────────────────────────────┐
│  L4 — HOST ADAPTERS                                                       │
│  ┌────────────────────────┐  ┌────────────────────────┐  ┌─────────────┐  │
│  │ Android Input Adapter  │  │ Android Observe Adapter│  │ Shell/Cmd   │  │
│  │  • dispatchGesture     │  │  • AccessibilityNode   │  │ Adapter     │  │
│  │  • MotionEvent legacy  │  │  • ScreenCapture       │  │             │  │
│  │  • DisplayManager      │  │                        │  │             │  │
│  └────────────────────────┘  └────────────────────────┘  └─────────────┘  │
│         │ implements               │ implements               │ implements │
├─────────┼──────────────────────────┼──────────────────────────┼───────────┤
│  L3 — PORTS (core-owned interfaces)                                       │
│   InputPort · ObservationPort · ShellPort · ClockPort ·                    │
│   DisplayPort · PermissionResolver · CapabilityProvider · ResultChannel    │
├──────────────────────────────────────────────────────────────────────────┤
│  L2 — ENGINE SERVICES                                                     │
│  ┌───────────────┐ ┌───────────────┐ ┌──────────────┐ ┌────────────────┐  │
│  │ Statement     │ │ Expression    │ │ Gesture      │ │ Flow/Scheduler │  │
│  │ Registry +    │ │ Evaluator     │ │ Geometry     │ │ Runtime        │  │
│  │ Lifecycle     │ │ (handles)     │ │ (pure math)  │ │ (async q2/x0)  │  │
│  └───────────────┘ └───────────────┘ └──────────────┘ └────────────────┘  │
├──────────────────────────────────────────────────────────────────────────┤
│  L1 — DOMAIN CORE                                                         │
│  Flow model · Statement AST · Result types · Capability enums ·           │
│  Exception taxonomy · Versioned serialization contracts                   │
└──────────────────────────────────────────────────────────────────────────┘
                       ▲ everything above depends on L1
                       ✕ L1 depends on nothing external

  CROSS-CUTTING:  CapabilityProvider  ·  Structured Logging  ·  Diagnostics
  PERSISTENCE:    Serializer (versioned)  ◄──→  durable flow store
```

**Dependency rule (strict: downward only):**

| From → To | L1 | L2 | L3 | L4 | External SDK |
|-----------|:--:|:--:|:--:|:--:|:------------:|
| **L1 Domain** | — | ✕ | ✕ | ✕ | ✕ |
| **L2 Services** | ✓ | — | ✓ | ✕ | ✕ |
| **L3 Ports** | ✓ | ✕ | — | ✕ | ✕ |
| **L4 Adapters** | ✓ | ✓ | ✓ | — | ✓ |

- L2 *depends on* L3 (it consumes ports) but never *implements* them.
- L4 *implements* L3 and is the **only** layer permitted to reference a platform SDK.
- The arrow of control at runtime is L2 → (via port) → L4. The arrow of compile-time dependency is L4 → L3 ← L2. Inversion is the whole point.

### Layer responsibilities in one line each

| Layer | Responsibility | Must never |
|-------|----------------|------------|
| **L1 Domain** | Define *what a flow is*, *what a result is*, *what can fail why*. | Reference any port, any platform type. |
| **L2 Services** | Execute a flow: schedule statements, evaluate expressions, build gesture geometry, resolve async results. | Import a platform SDK; construct an adapter. |
| **L3 Ports** | Declare the contract between engine and host in core vocabulary. | Contain logic, state, or platform types. |
| **L4 Adapters** | Translate each port call into a platform-specific effect and translate platform callbacks back into port results. | Contain flow/business policy; make scheduling decisions. |

---

## 1.3 Ports & Adapters

### 1.3.1 The port taxonomy

Ports are grouped by concern. Every port is defined in the core and expressed in core vocabulary (domain types, capability enums, result records) — never in platform types.

```text
                         ┌─────────────────────────┐
                         │      L2 Engine          │
                         └───────────┬─────────────┘
        ┌───────────────┬────────────┼─────────────┬────────────────┐
        ▼               ▼            ▼             ▼                ▼
  ┌──────────┐   ┌────────────┐  ┌────────┐  ┌───────────┐   ┌──────────────┐
  │InputPort │   │Observation │  │Shell   │  │Display /  │   │Capability/   │
  │(actuator)│   │Port(sensor)│  │Port    │  │Clock Port │   │Permission +  │
  │          │   │            │  │        │  │           │   │ResultChannel │
  └────┬─────┘   └─────┬──────┘  └───┬────┘  └─────┬─────┘   └──────┬───────┘
       │               │             │             │                │
  ┌────▼─────┐   ┌─────▼──────┐  ┌───▼────┐  ┌─────▼─────┐   ┌──────▼───────┐
  │ Android  │   │ Android    │  │ Shell  │  │ Android   │   │ Android      │
  │ Input    │   │ Observe    │  │ Cmd    │  │ Display/  │   │ Service      │
  │ Adapter  │   │ Adapter    │  │ Adapter│  │ Clock     │   │ Lifecycle    │
  └──────────┘   └────────────┘  └────────┘  └───────────┘   └──────────────┘
```

### 1.3.2 Port contracts (illustrative signatures)

The following are **contract sketches**, not final types. They establish the shape the adapter must honor. All types used below (`Gesture`, `Path`, `Point`, `DisplayInfo`, `Outcome<T>`, `Capability`) are domain types.

```text
// ─── Actuator port ───────────────────────────────────────────
interface InputPort {
    // Native dispatch path (where supported).
    // Returns a handle whose completion resolves asynchronously.
    Future<Outcome<Unit>> dispatch(Gesture gesture, DisplayRef display);

    // Legacy synthesis path; semantics identical, mechanism differs.
    Future<Outcome<Unit>> synthesize(Gesture gesture, DisplayRef display);

    boolean supports(Capability cap);   // e.g. NATIVE_GESTURE_DISPATCH, MULTI_DISPLAY
}

// ─── Sensor port ─────────────────────────────────────────────
interface ObservationPort {
    Future<Outcome<ScreenSnapshot>> capture(DisplayRef display);
    Future<Outcome<UiNode>> queryUi(UiQuery query);
    boolean supports(Capability cap);   // e.g. SCREEN_CAPTURE, UI_TREE_ACCESS
}

// ─── Environment ports ───────────────────────────────────────
interface DisplayPort {
    DisplayInfo resolve(DisplayRef ref);   // size, density, realSize
}
interface ClockPort {
    long uptimeMillis();
}
interface ShellPort {
    Future<Outcome<ShellResult>> exec(String commandLine, ShellOptions opts);
}

// ─── Governance ports ────────────────────────────────────────
interface PermissionResolver {
    Outcome<Unit> ensureDeclared(PermissionSet required);
}
interface CapabilityProvider {
    boolean has(Capability cap);
}
interface ResultChannel {           // single sink for async resolution
    void resolve(StatementHandle handle, Outcome<Unit> outcome);
}
```

**Port design rules:**

1. **No platform types leak.** If an adapter needs a platform-specific opaque value (a service reference, a native handle), it is stored *inside the adapter* and addressed by an adapter-local identifier — never passed through the port.
2. **Capability checks travel with the port.** `InputPort.supports(...)` and `ObservationPort.supports(...)` let the core choose a strategy without knowing the host, satisfying **P2**.
3. **Completion is uniform.** Every effectful call returns `Future<Outcome<T>>` or reports through `ResultChannel`. There is exactly one success/failure vocabulary (**P3**).
4. **Ports have no state of their own.** State (open handles, active pointers, scheduled ticks) lives in the adapter; ports are stateless facades or thin handles.

### 1.3.3 How a gesture statement maps to ports (worked example)

This traces the source material's dual-path actuator — native `dispatchGesture` vs. legacy `MotionEvent` synthesis — through the architecture, proving the abstraction holds.

```text
L1/L2  Statement: Gesture(tap, p0, p1, speed)
          │
          ▼
L2     GestureGeometry.build(...)          ← pure math (path, duration)
          → Gesture { strokes[], duration }      no platform types
          │
          ▼
L2     Strategy selection via capabilities (P2):
          if caps.has(NATIVE_GESTURE_DISPATCH)  → input.dispatch(gesture, display)
          else                                  → input.synthesize(gesture, display)
          │
          ▼
L3     InputPort  (core boundary — the fork lives in the CORE, not the adapter)
          │
   ┌──────┴───────┐
   ▼              ▼
L4 Android      L4 Android
   InputAdapter    LegacyInputAdapter
   ├─setDisplayId  ├─ticker loop (ClockPort)
   ├─addStroke     ├─PathMeasure.getPosTan
   ├─dispatch      ├─MotionEvent.obtain
   └─callback→     └─inject bridge
      ResultChannel   └─complete→ResultChannel
          │
          ▼
L3/L2  ResultChannel.resolve(handle, Outcome(success|failure))
          │
          ▼
L2     Scheduler resumes flow (async statement x0-equivalent)
```

Three architectural guarantees are visible in this trace:

- The **geometry** (path length → duration via `speed × density`) is pure and lives in L2; it is unit-testable without a device.
- The **fork** between native and legacy paths is a *capability decision in the core* (`caps.has(...)`), not a version check buried in an adapter. The adapters are unaware there is a sibling.
- Both paths converge on the **same** `ResultChannel` contract, so the scheduler's resume logic is identical regardless of mechanism. This is what makes the legacy path (arbitrarily complex — a `postDelayed` ticker, multi-pointer `ACTION_DOWN/MOVE/UP` ordering, `PathMeasure` interpolation) a drop-in replacement rather than a parallel code path in the engine.

### 1.3.4 Adapter isolation contract

Each L4 adapter obeys the following invariant set:

| Invariant | Meaning |
|-----------|---------|
| **Single SDK surface** | Only L4 may import platform packages. A CI check (`dependency-gate`) fails the build if L1–L3 reference a platform namespace. |
| **Lifecycle binding** | Adapters that require a host service (e.g., an `AccessibilityService` singleton) register/unregister with the host lifecycle and expose readiness through `CapabilityProvider`; the core never polls for readiness directly. |
| **Callback normalization** | Platform callbacks (`onCompleted`/`onCancelled`, injection success/failure, service connect/disconnect) are translated into the port's `Outcome` vocabulary before crossing L3. |
| **No policy** | An adapter never decides *whether* to run a gesture, *how* to retry, or *what* to do on failure. It reports; the core decides. |
| **Swappable per capability** | For any capability flag there is exactly one adapter selected at composition time; adapters are selected by a **composition root**, not discovered at call sites. |

### 1.3.5 Composition root

Adapters are wired in a single composition root that owns the only platform-aware startup logic:

```text
CompositionRoot(hostContext):
    caps = AndroidCapabilityProbe(hostContext)          // SDK_INT → Capability set
    input = caps.has(NATIVE_GESTURE_DISPATCH)
                ? NativeInputAdapter(serviceRegistry)
                : LegacyInputAdapter(clockPort, injectBridge)
    observe = caps.has(SCREEN_CAPTURE)
                ? AndroidObserveAdapter(serviceRegistry)
                : NullObservationAdapter()              // sensor disabled, not faked
    engine = Engine(l1


## 2. Core Modules: Input Injection (GestureIR, Android Native/Legacy/Root, PC)

# 2. Core Modules: Input Injection (GestureIR, Android Native/Legacy/Root, PC)

The Input Injection subsystem is the **output terminal** of the macro engine: it takes an abstract, backend-agnostic gesture description and *materializes* it on the target environment. Every other module (recording, playback, scheduling, state detection) ultimately funnels gestures into this layer. The design goal is a **single intermediate representation** (`GestureIR`) that all producers emit and all consumers accept, with transport-specific backends that translate the IR into whatever the host allows.

This section defines the IR, the backend taxonomy, and the transport implementations for Android (Native Accessibility, Legacy MotionEvent, Root) and PC.

---

## 2.1 GestureIR — The Intermediate Representation

`GestureIR` is the *contract* between producers (recorder, script API, replayer) and consumers (injection backends). It must be expressive enough for multi-touch, timing-faithful, and cheap to serialize for cross-process/cross-device transport.

### 2.1.1 Data Model

```text
GestureIR
 ├─ metadata
 │   ├─ id            : string (UUID)
 │   ├─ createdAt     : epoch_ms
 │   ├─ source        : enum { RECORD, SCRIPT, IMPORT, REPLAY }
 │   └─ targetHint    : enum { AUTO, ANDROID_NATIVE, ANDROID_LEGACY,
 │                             ANDROID_ROOT, PC }
 └─ strokes          : Stroke[]      (ordered, non-overlapping in (pointer, time))
     └─ Stroke
         ├─ pointerId : int (0..N-1)   // stable identity across the gesture
         ├─ points    : Sample[]
         │   └─ Sample
         │       ├─ t      : int   // ms, relative to gesture start (>=0, monotonic)
         │       ├─ x,y    : float // normalized [0,1] OR absolute px (see space)
         │       └─ pressure : float (0..1, default 1)
         └─ space     : enum { NORMALIZED, PIXEL }

GestureIR.space        // global default; per-sample override allowed
GestureIR.duration     : int   // derived = max(t) over all samples
```

### 2.1.2 Invariants

Backends may reject an IR that violates these; producers SHOULD normalize first.

1. **Monotonic time.** For each stroke, `t[i] >= t[i-1]`. Global `duration = max(t)`.
2. **Pointer uniqueness.** No two strokes share a `pointerId`.
3. **Non-negative time.** `t >= 0`; negative sample times are clamped to 0 by the normalizer, not by backends.
4. **Bounded coordinates.** In `NORMALIZED` space, `x,y ∈ [0,1]` (producers clamp; backends assume clamped).
5. **Duration ceiling.** Backends that depend on OS gesture limits enforce a max duration (see §2.3.2). The IR itself imposes no ceiling.

### 2.1.3 Primitive Gesture Builders

The high-level "tap / long-press / swipe / pinch" vocabulary is a *convenience layer* that compiles down to `GestureIR`. This mirrors how production macro engines synthesize these:

| Primitive | IR Construction |
|-----------|------------------|
| `TAP(x, y)` | 1 stroke, 1 point held for `tapTimeout/2` (a zero-length path held in time) |
| `LONG_PRESS(x, y)` | 1 stroke, 1 point held for `longPressTimeout + ε` |
| `DOUBLE_TAP(x, y)` | 2 strokes (sequential, same pointerId reused), gap = `tapTimeout/2` |
| `SWIPE(p0, p1, speed)` | 1 stroke, 2 points; duration = `pathLength / (speed · density)` |
| `PINCH(center, delta, out)` | 2 strokes; symmetric line paths converging/diverging at `center` |

```pseudo
function buildSwipe(p0, p1, speedPxPerSec):
    len   = distance(p0, p1)
    durMs = round((len / speedPxPerSec) * 1000)
    if durMs <= 0: throw InvalidSpeed
    return Stroke{ pointerId=0,
                   points=[{t=0, x=p0.x, y=p0.y},
                           {t=durMs, x=p1.x, y=p1.y}] }
```

> **Design note:** duration is always *derived* from geometry + speed for movement gestures, never a free parameter, because OS timing constants are the ground truth. A tap's "duration" is deliberately a fraction of the platform tap timeout so it registers as a tap and not a long-press.

---

## 2.2 Backend Selection

### 2.2.1 Capability Probe

At engine startup, the injector **probes** the host and caches a capability set:

```pseudo
capabilities = {
    canDispatchGesture : SDK_INT >= 24 && hasAccessibilityService(),
    canMultiDisplay    : SDK_INT >= 30,
    canRootInject      : hasRootShell() && canReachInputManager(),
    hasPCBridge        : isPCHost() && bridgeConnected()
}
```

### 2.2.2 Selection Policy

`targetHint` narrows the candidate set; the policy then picks the **highest-fidelity available** backend, degrading gracefully:

```text
resolveBackend(ir):
    candidates = filter by ir.targetHint
    for b in ORDERED_BY_FIDELITY:      // see table below
        if b.available() and b.supports(ir):
            return b
    throw NoUsableBackend(ir.targetHint)
```

| Fidelity rank | Backend | Requires | Supports |
|---|---|---|---|
| 1 (highest) | `AndroidNative` | API ≥ 24 + AccessibilityService | multi-touch, timing, multi-display |
| 2 | `AndroidRoot` | root shell + InputManager reachable | multi-touch, arbitrary injection |
| 3 | `AndroidLegacy` | API < 24 (or Accessibility denied) | synthetic `MotionEvent` via input bridge |
| 4 | `PC` | desktop host + bridge | mouse/keyboard synthesis; single "pointer" typically |

If `targetHint == ANDROID_LEGACY` but a native service is available, the engine **does not** silently upgrade — an explicit hint is honored so recording/playback stay reproducible across devices with different capabilities.

---

## 2.3 Android Native Backend (`AndroidNative`)

The preferred path. Uses the accessibility gesture API, which the OS internally schedules and injects in a way that survives most anti-tampering checks.

### 2.3.1 Dispatch

```pseudo
function dispatch(ir):
    assert(canDispatchGesture)
    gb = GestureDescription.Builder()

    if capabilities.canMultiDisplay and ir.displayId != null:
        gb.setDisplayId(ir.displayId)     // API 30+ only

    for stroke in ir.strokes:
        path = Path()
        path.moveTo(stroke.points[0])
        for p in stroke.points[1:]:
            path.lineTo(p)
        gb.addStroke(StrokeDescription(path, stroke.startTime, stroke.duration))

    gesture = gb.build()
    service.dispatchGesture(gesture, callback, handler)
```

### 2.3.2 Constraints and Guards

- **Max duration:** `GestureDescription.getMaxGestureDuration()` (currently **60,000 ms**). Any IR exceeding it is **rejected with a clear error** rather than truncated — silent truncation produces ghost gestures.
- **Max stroke count:** `getMaxStrokeCount()` (currently **10**). IRs with more pointers must be split into sequential phases by a higher layer.
- **Async completion:** `dispatchGesture` is asynchronous. The backend returns only *after* `onCompleted` (success) or `onCancelled` (failure). The engine treats cancellation as a failed injection and surfaces it to the caller.

```pseudo
onCompleted(g):  resolveDispatch(OK)
onCancelled(g):  resolveDispatch(FAIL_OS_CANCELLED)
```

> **Recovery nuance:** the result must be routeable back to the specific dispatch call, not a global boolean. The callback carries the `GestureDescription` reference used as the correlation key into a pending-request map.

### 2.3.3 Service Lifecycle Contract

The native backend delegates to a **singleton accessibility service**. The service exposes:

- `static instance()` — volatile, double-checked-locking access to the *active* instance.
- `static onServiceConnected / onServiceDisconnected` — lifecycle callbacks that flip availability.

The backend registers an **observer** on connect/disconnect so it can fail fast (and re-establish) when the OS kills and rebinds the service:

```pseudo
class NativeBackend implements ServiceObserver:
    available() = (instance() != null)

    onConnected(svc):
        # resume any queued dispatches
    onDisconnected():
        # reject in-flight requests with FAIL_SERVICE_LOST
        # do NOT retry blindly (may loop if permission revoked)
```

---

## 2.4 Android Legacy Backend (`AndroidLegacy`)

For API < 24, or when accessibility-gesture dispatch is unavailable, the engine synthesizes `MotionEvent`s directly and injects them through a privileged bridge.

### 2.4.1 Architecture — Ticker + Interpolation

Unlike native dispatch (one call, OS schedules), the legacy path is a **driven simulation loop**: a handler ticker repeatedly computes the state of every active pointer at the current timestamp and emits `DOWN / MOVE / UP` events.

```text
main loop (scheduled via postDelayed, capped at 100ms per wake):
  now = uptimeMillis()
  processBoundaries(now):
     - emit UP for strokes whose endTime ≤ now
     - emit DOWN for strokes whose startTime ≤ now
     - emit MOVE for all currently-active pointers
  if no active pointers: complete(OK)
  else: reschedule at next boundary (≤ 100ms)
```

### 2.4.2 Per-Pointer Interpolation

For each active pointer, position is interpolated along its path by time fraction:

```pseudo
function positionAt(stroke, now):
    f = clamp((now - stroke.startTime) / (stroke.endTime - stroke.startTime), 0, 1)
    x = lerp(stroke.points[0].x, stroke.points[-1].x, f)   // generalized to polyline
    y = lerp(stroke.points[0].y, stroke.points[-1].y, f)
    return (x, y)
```

(Emission is skipped when the interpolation did not change from the last emitted coordinate **unless** a forced boundary event is occurring — this avoids flooding the input queue with redundant MOVE events.)

### 2.4.3 MotionEvent Construction

```pseudo
event = MotionEvent.obtain(
    downTime, eventTime,
    action | (pointerIndex << ACTION_POINTER_INDEX_SHIFT),
    pointerCount, props[], coords[],
    /*metaState*/ 0, /*button*/ 0,
    /*xPrecision*/ 1, /*yPrecision*/ 1,
    /*deviceId*/ 0, /*edgeFlags*/ 0,
    /*source*/ INJECTED_FLAG, /*flags*/ 0)
bridge.inject(event)
event.recycle()
```

Where the injection **bridge** is a pluggable seam:

- On privileged/system builds it may bind directly to the platform input manager.
- On root builds it shells out to an input-injection helper (see §2.5).
- The bridge abstracts the actual `injectInputEvent`-equivalent so the ticker logic is transport-independent and unit-testable.

### 2.4.4 Timing Fidelity Caveats

- Ticker wakeups are **coarse** (OS timer resolution + scheduler jitter), so fast swipes lose intermediate MOVE samples. The interpolation model means the *path* remains correct even if few MOVE events are emitted.
- Event timestamps use `SystemClock.uptimeMillis()` and are forced **monotonic**; a regression in the clock must not produce out-of-order events.

---

## 2.5 Android Root Backend (`AndroidRoot`)

Used when accessibility is unavailable or insufficient but the device is rooted. Provides the most direct injection path and is **not** subject to the accessibility gesture-duration cap.

### 2.5.1 Injection Path

```pseudo
function dispatch(ir):
    ensureRootShell()
    for phase in expandToEventStream(ir):     // reuse legacy ticker/interpolator
        shell.write("input inject ...")        // or helper binary consuming
        shell.write(serializeEvent(phase))     // a compact binary event stream
    return shell.awaitExit() == 0 ? OK : FAIL
```

Two supported sub-transports:

1. **Helper binary** — a small native bridge that reads a serialized event stream on stdin and calls the platform injection API from a privileged context. Preferred because it batches and avoids per-event process spawn cost.
2. **`input` shell command** — fallback for tap/swipe only (no multi-touch, coarse timing). Only used when no helper is installed.

### 2.5.2 Constraints

- **Latency:** shell round-trips dominate. The helper binary path is chosen whenever available.
- **Safety gating:** root injection can affect *any* app and the system UI, so the engine requires an **explicit per-Automation root enable**, never auto-detected consent.
- **Failure semantics:** non-zero exit or missing helper = `FAIL_ROOT_UNAVAILABLE`; the resolver may then degrade to `AndroidLegacy` if the hint allowed it.

---

## 2.6 PC Backend (`PC`)

For desktop macro hosts, the backend maps `GestureIR` onto mouse/keyboard primitives via a platform bridge. Unlike the Android multi-touch model, PC synthesis is typically **single-pointer** (one cursor) plus discrete button/key events.

### 2.6.1 Mapping Rules

| IR Construct | PC Translation |
|---|---|
| 1 stroke, holding button → release | click (or long-press → hold then release) |
| 2 sequential 1-point strokes | double-click |
| 1 stroke with ≥ 2 points | cursor move (optionally with button held for a drag) |
| ≥ 2 simultaneous strokes | **not natively supportable**; downgraded to sequential with a warning |

```pseudo
function dispatch(ir):
    assert(bridge.connected())
    if ir.strokes.length > 1 and overlapping(ir):
        warn("PC backend: multi-touch downgraded to sequential")
    for stroke in ir.strokes:
        bridge.moveTo(stroke.points[0])
        if stroke.points.length == 1:
            bridge.down(); bridge.sleep(stroke.duration); bridge.up()
        else:
            # drag: down, interpolated move, up
            bridge.down()
            for p in stroke.points[1:]:
                bridge.moveTo(p)
            bridge.up()
```

### 2.6.2 Timing Model

PC bridges lack the OS-scheduled gesture semantics of Android, so the backend performs its own **pacing**: it emits moves at a target cadence (e.g., every 8–16 ms) and honors the IR's relative timestamps by sleeping between samples. Sub-tick slack is absorbed into the next move.

---

## 2.7 Common Backend Interface

All backends implement a uniform contract so higher layers never branch on transport:

```pseudo
interface InputBackend:
    name()              : string
    capabilities()      : CapabilitySet
    available()         : bool
    supports(ir)        : bool        // structural check (stroke count, duration, space)
    dispatch(ir)        : Result      // Result ∈ { OK, FAIL_* }
    cancel()            : void        // abort in-flight dispatch (best-effort)

    # events
    onAvailabilityChanged(listener)   // service connect/disconnect, bridge up/down
```

`Result` values are normalized across backends:

```text
OK
FAIL_NOT_AVAILABLE
FAIL_REJECTED_CONSTRAINT   // e.g., exceeds max duration / stroke count
FAIL_CANCELLED             // OS or backend cancel
FAIL_SERVICE_LOST          // accessibility/bridge disconnected mid-dispatch
FAIL_TRANSPORT             // root shell error / bridge I/O error
```

---

## 2.8 Transport & Serialization

The IR must survive three boundaries: **in-process** (direct call), **cross-process** (engine ↔ injection helper), and **cross-device** (recorded on A, replayed on B).

### 2.8.1 Wire Format

A compact binary framing is used for cross-process/device transport (avoid JSON overhead for high-sample-rate strokes):

```text
[u8  version]
[u8  space]
[u32 strokeCount]
  per stroke:
    [u16 pointerId]
    [u16 sampleCount]
      per sample: [u32 t][i32 x*Q][i32 y*Q][u8 pressure]   // Q = fixed-point scale
```

- Coordinates in `NORMALIZED` space are quantized to fixed-point `Q` (e.g., 1/65536) to keep payloads deterministic and diff-friendly.
- `PIXEL` space is serialized as-is and is **not portable** across devices; the engine warns when replaying a `PIXEL` IR on a host with different resolution.

### 2.8.2 Resolution Mismatch Handling

A recorded IR is almost always `NORMALIZED`, letting replay scale to the target's real display size:

```pseudo
function denormalize(ir, displaySize):
    for s in ir.strokes:
        if s.space == PIXEL: warn("PIXEL-space stroke on rescaled replay")
        for p in s.points:
            p.x *= displaySize.width
            p.y *= displaySize.height
```

---

## 2.9 Error Handling Summary

| Condition | Backend | Result | Caller-visible behavior |
|---|---|---|---|
|


## 3. Core Modules: Screen Capture & State Detection (MediaProjection, Accessibility, PC)

# 3. Core Modules: Screen Capture & State Detection (MediaProjection, Accessibility, PC)

This section specifies the three interchangeable **perception backends** that feed the macro engine's state-detection layer. All backends implement a single canonical contract (`ICaptureBackend`) so that the decision/gesture layers (Sections 4–6) are agnostic to how pixels or the UI tree are obtained.

## 3.1 Design Goals

| Goal | Rationale |
|------|-----------|
| **Backend abstraction** | Android can capture frames via MediaProjection (high-fidelity pixels) *or* Accessibility (UI tree + partial screenshots), and on PC via OS-level capture APIs. The engine must not branch on platform inside detection logic. |
| **Frame-or-tree duality** | Some detectors need raw pixels (template matching, OCR); others need structured nodes (AccessibilityNodeInfo / UIA trees). The contract must expose both, with graceful degradation. |
| **Latency budgets** | Capture is the hot path. Each backend declares `maxFps`, `avgLatencyMs`, and whether it supports **region** and **downscale** hints so detectors can trade fidelity for speed. |
| **Deterministic lifecycle** | Capture sessions are resource-heavy (MediaProjection token, system dialogs, framebuffers). Lifecycle is explicit: `probe → start → frames → stop → release`. |

## 3.2 The `ICaptureBackend` Contract

```text
interface ICaptureBackend {
    // Capability negotiation
    Capabilities probe();                       // sync, cheap; reports what's available
    // Lifecycle
    void  start(CaptureConfig cfg) throws CaptureException;
    void  stop();
    void  release();
    // Pull models
    Frame? nextFrame(Timeout t);                // pixels (may be null if tree-only)
    UiNode? rootTree(Timeout t);                // accessibility / UIA tree
    // Async model (preferred for high fps)
    void  subscribe(FrameListener l, CaptureConfig cfg);
    void  unsubscribe(FrameListener l);
}

struct Capabilities {
    bool canCaptureFrames;
    bool canCaptureTree;
    bool canInjectInput;        // NB: emission is a *separate* module; see §5
    int  maxFps;
    Size[] supportedScales;     // e.g. {1.0, 0.5, 0.25}
    bool supportsRegion;
    bool requiresUserConsent;   // MediaProjection = true
}

struct CaptureConfig {
    Region?  region;            // null = full screen
    float    scale;             // 1.0 = native
    int      targetFps;
    PixelFormat format;         // RGBA_8888, etc.
    bool     includeTree;       // request tree alongside frames
}
```

> **Note on input injection:** This section is strictly **perception**. Gesture *emission* (Accessibility `dispatchGesture`, MotionEvent injection, ADB/PC synthetic input) belongs to the touch/interaction module. `ICaptureBackend.canInjectInput` is only an advisory flag so the orchestrator can select a *paired* backend — it never emits anything here.

The engine ships exactly one concrete implementation per platform, selected at runtime by a `BackendSelector` (§3.6):

```text
Android:  MediaProjectionBackend  |  AccessibilityBackend  |  (fallback) AdbScreenBackend
PC:       PcCaptureBackend (DXGI / X11 / Wayland / CGDisplay)
```

## 3.3 Android Backend A — MediaProjection (Pixel Capture)

MediaProjection provides **high-fidelity, low-latency frames** and is the preferred backend for template matching and OCR. It requires **explicit user consent** per session (the system `MediaProjection` permission dialog cannot be bypassed).

### 3.3.1 Acquisition flow

```text
1. Obtain MediaProjectionManager from Context.
2. Intent i = mgr.createScreenCaptureIntent();  → launch for user consent.
3. onActivityResult(resultCode, data) → MediaProjection mp =
       mgr.getMediaProjection(resultCode, data);
4. Register MediaProjection.Callback (onStop → tear down virtual display).
5. Create ImageReader(width, height, PixelFormat.RGBA_8888, maxImages=2).
6. VirtualDisplay vd = mp.createVirtualDisplay(
       "macro-capture", w, h, densityDpi,
       DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
       imageReader.getSurface(), null, handler);
```

### 3.3.2 Frame pumping (`nextFrame` / `subscribe`)

```text
frameloop:
    onImageAvailable(ImageReader r):
        Image img = r.acquireLatestImage();        // drop-oldest: never block
        if img == null: return
        Frame f = copyPlanesToFrame(img, scaleHint);
        img.close();
        listeners.forEach(l -> l.onFrame(f));
```

Key rules:

- **`acquireLatestImage()`** (not `acquireNextImage()`) so a slow consumer cannot stall the producer; stale frames are dropped by design.
- **Copy out of the `Image`** before `close()` — the buffer is recycled by the reader immediately.
- **Scale on GPU when available** (render into a `SurfaceTexture` + `GLES` pass for 0.5×/0.25×); otherwise CPU-nearest-neighbor. The engine prefers GPU downscale for >1080p sources.
- `maxImages = 2` limits internal buffering; higher values increase latency without benefit.

### 3.3.3 Region capture

MediaProjection always mirrors the full display in Android ≤ 13. Region cropping is therefore a **post-capture** operation performed on the `Frame` buffer (zero-copy view into the plane array when stride permits). The engine documents this honestly — `supportsRegion = true` means *effective* region delivery to detectors, not native region capture.

### 3.3.4 Failure modes

| Condition | Behavior |
|-----------|----------|
| User denies consent | `start()` throws `CaptureException(ConsentDenied)`; selector falls through to Accessibility backend |
| `onStop` callback (user revokes, display off) | Session marked dead; all `subscribe` listeners receive `onError(SessionEnded)`; no auto-restart (would re-prompt consent) |
| Secure window (`FLAG_SECURE`) | Black frames — detectors must tolerate; backend sets `Frame.secureSuspect = true` via a cheap luminance probe |
| Rotation / resolution change | Backend emits `onConfigChanged`; virtual display is rebuilt; frames resume |

## 3.4 Android Backend B — Accessibility (UI Tree + Partial Pixels)

The Accessibility backend wraps an `AccessibilityService` instance (the engine's own service, analogous to the `EMMAccessibility` singleton pattern) and exposes the **structured UI tree**. It is *mandatory* on any device where MediaProjection consent is unavailable, and it is the **only** backend that yields semantic state (text, bounds, clickable flags) without OCR.

### 3.4.1 Tree acquisition

```text
rootTree(timeout):
    root = service.getRootInActiveWindow();          // may be null early
    if root == null: retry until timeout (backoff 50ms)
    return adapt(root);                               // -> UiNode, lazily materialized
```

`UiNode` is a **lazy snapshot adapter** over `AccessibilityNodeInfo`:

```text
struct UiNode {
    string   text;            // node.getText() ?? ""
    string   contentDesc;
    string   className;
    string   viewId;          // package:id
    Rect     boundsInScreen;
    Rect     boundsInParent;
    bool     clickable, focusable, enabled, visible;
    int      depth;
    UiNode[] children;        // materialized on access
}
```

- **Recycling discipline:** each `AccessibilityNodeInfo` obtained is `recycle()`d immediately after its fields are copied into the immutable `UiNode`. Nodes are **never** held across frames — this prevents the well-known `AccessibilityNodeInfo` leak/`IllegalStateException` class of bugs.
- **Depth/size caps:** traversal is bounded (`maxDepth = 32`, `maxNodes = 5000`) to protect against pathological trees; truncation sets `UiNode.truncated = true`.

### 3.4.2 Tree-change events

The service registers `AccessibilityServiceInfo` with `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` (API 21+) and subscribes to `TYPE_WINDOW_STATE_CHANGED`, `TYPE_WINDOW_CONTENT_CHANGED`, and `TYPE_VIEW_SCROLLED`. These are **debounced** (default 32 ms coalescing window) before a `StateChangeEvent` is emitted to the detection layer — raw event storms are common during animations.

### 3.4.3 Pixel fallback (`takeScreenshot`)

On **API 30+**, `AccessibilityService.takeScreenshot(displayId, executor, callback)` provides a static full-screen frame — *no consent dialog*, but **rate-limited** by the system (typically ~1 fps; exceeding it throws `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT`). The backend:

- Exposes this as `nextFrame()` with `capabilities.maxFps = 1`.
- Treats `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` and `ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT` as non-fatal (returns `null`, caller retries).
- On **API < 30**, `nextFrame()` returns `null` and `canCaptureFrames = false`; the selector pairs this backend with `AdbScreenBackend` if ADB is available.

### 3.4.4 Capability matrix

| Capability | API 21–29 | API 30+ |
|------------|-----------|---------|
| `canCaptureTree` | ✅ | ✅ |
| `canCaptureFrames` | ❌ | ✅ (~1 fps, consent-free) |
| `canInjectInput` | ✅ (`dispatchGesture`, API 24+) | ✅ |
| `supportsRegion` | — (tree only) | ✅ (crop post-screenshot) |

## 3.5 PC Backend — OS-Level Capture

The PC backend mirrors the same contract, with frames as the primary modality and tree access provided by OS accessibility APIs where available.

| Platform | Frame source | Tree source | Notes |
|----------|--------------|-------------|-------|
| **Windows** | DXGI Desktop Duplication (or Windows.Graphics.Capture) | UI Automation (UIA) | DXGI gives dirty-rect deltas; backend coalesces into full frames at `targetFps` |
| **macOS** | ScreenCaptureKit / CGDisplayStream | AXUIElement (Accessibility API) | Requires screen-recording TCC permission; `requiresUserConsent = true` |
| **Linux (X11)** | XComposite + XShm | AT-SPI | Region capture is native via `XGetImage` on subwindows |
| **Linux (Wayland)** | PipeWire + xdg-desktop-portal | AT-SPI (limited) | Consent handled by portal dialog; region capture may be portal-gated |

Implementation notes:

- **Dirty-rect optimization:** DXGI and ScreenCaptureKit emit damage regions. When `CaptureConfig.region` is set, the backend **clips** the damage region and only allocates a new `Frame` on genuine pixel change — this is the single largest CPU win on PC.
- **Pacing:** the PC backend is *push*-based (OS delivers frames); a token-bucket enforces `targetFps`, dropping the oldest frame when the consumer lags — identical drop-oldest semantics to Android MediaProjection.
- **Tree availability is advisory:** many PC windows (custom-rendered games, Electron without a11y) expose an empty tree. `canCaptureTree` reflects a *runtime probe* (walk one level, count nodes), not a static platform fact.

## 3.6 Backend Selection & Composition

`BackendSelector` resolves a backend at engine start and re-resolves on failure:

```text
select():
    if platform == PC:
        return PcCaptureBackend
    // Android
    if mediaProjectionGrantedCached:
        return MediaProjectionBackend            // best fidelity
    if accessibilityServiceConnected:
        return AccessibilityBackend              // tree + slow frames
    if adbBridgeAvailable:
        return AdbScreenBackend                  // screencap over ADB, dev only
    throw NoCaptureBackendAvailable
```

**Fallback ordering is a policy, not a hard truth** — the orchestrator may *prefer* tree-first detection even when MediaProjection is granted (UI-node matching is O(nodes) vs. O(pixels)). The selector therefore returns an **ordered list**; the detection layer (Section 4) requests the modality it needs and the first capable backend in the list is used per-request.

```text
resolve(modality):  // modality ∈ {FRAMES, TREE}
    for backend in selectionOrder:
        if modality == FRAMES and backend.capabilities.canCaptureFrames: return backend
        if modality == TREE   and backend.capabilities.canCaptureTree:   return backend
    return null   // detector must degrade (e.g., skip, use cached state)
```

## 3.7 State-Detection Outputs

Backends above produce **raw percepts**. The state-detection layer normalizes them into a single `ScreenState` snapshot consumed by the macro decision graph:

```text
struct ScreenState {
    timestampMs: long;
    source: BackendKind;         // MEDIA_PROJECTION / ACCESSIBILITY / PC / ADB
    frame: Frame?;               // pixels, if modality allowed
    tree: UiNode?;               // root, if available
    window: WindowInfo;          // package/activity/title, focus
    secureSuspect: bool;         // black-frame heuristic (FLAG_SECURE)
}
```

Invariants enforced by the detection layer (not the backends):

1. **Timestamps are monotonic** (`SystemClock.uptimeMillis()` / PC steady clock). A `ScreenState` newer than the last is never replaced by an older one.
2. **`frame` and `tree` are time-coherent within a tolerance** (default 50 ms): if the tree is fetched after the frame, the pair is only emitted if `|Δ| ≤ tolerance`; otherwise the detection layer re-acquires.
3. **Null-safe:** a detector that needs `frame` but receives `frame = null` (tree-only backend) must return `UNKNOWN`, never a false positive. The contract documents this as an explicit detector obligation (Section 4).

## 3.8 Lifecycle & Resource Rules

```text
┌────────┐  probe()  ┌─────────┐  start(cfg)  ┌────────┐
│  INIT  │──────────▶│ PROBED  │─────────────▶│ ACTIVE │
└────────┘           └─────────┘              └────────┘
                                                   │ stop()
                                                   ▼
                                              ┌─────────┐  release()
                                              │ STOPPED │───────────▶ INIT
                                              └─────────┘
        any state ──── release() ────▶ INIT (idempotent)
```

- `start()` is **not re-entrant**; calling twice throws `IllegalStateException`.
- `subscribe()` implicitly activates a session if `PROBED`; the first subscriber triggers `start()` with default config, captured backends are shared across subscribers (single virtual display / single screenshot pump).
- `stop()` synchronously ceases frame delivery and emits `Frame.Listener.onClosed()` to every subscriber; it does **not** release the consent token (that happens in `release()`), so the engine can restart without re-prompting.
- On Android, the `AccessibilityService` connection is a **process-lifetime singleton**; `release()` on the Accessibility backend never disconnects the service, it only clears per-session state.

## 3.9 Cross-Platform Summary

| Backend | Frames | Tree | Consent | Typical latency | Hot-path use |
|---------|--------|------|---------|-----------------|--------------|
| MediaProjection | ✅ full fps | ❌ | ✅ once/session | 16–50 ms | template/OCR |
| Accessibility (30+) | ✅ ~1 fps | ✅ | ❌ (service enabled once) | tree: 5–20 ms | UI-node state |
| Accessibility (<30) | ❌ | ✅ | ❌ | tree: 5–20 ms | UI-node state |
| ADB screencap | ✅ ~1–3 fps | ❌ | ✅ adb auth | 100–300 ms | dev/CI only |
| PC (DXGI/SCK/…) | ✅ full fps | ✅ (UIA/AX/AT-SPI, best-effort) | ✅ OS prompt | 8–30 ms | both |

The unification point is deliberate: **every detector in Section 4 is written against `ScreenState`, never against a backend class.** Adding a new backend (e.g., a future Android `HardwareBuffer` direct path) requires zero changes above this layer.


## 4. Core Modules: Service Lifecycle & Permissions (Broker, Resilience, Fallbacks)

# 4. Core Modules: Service Lifecycle & Permissions (Broker, Resilience, Fallbacks)

This section defines the architecture for the modules responsible for acquiring, holding, validating, and releasing privileged platform capabilities, and for gracefully degrading when those capabilities are unavailable. It is platform-agnostic in contract but grounded in the patterns observed in the reference implementations.

The core principle is **capability isolation**: no macro step queries or invokes platform services directly. All privileged operations funnel through a **Broker**, which owns the service lifecycle, enforces the permission model, and coordinates **Resilience** (retry/health) and **Fallback** (degraded-path) strategies.

---

## 4.1 Responsibilities Overview

The lifecycle subsystem is composed of three cooperating modules plus a shared contract layer:

```
┌──────────────────────────────────────────────────────────────────────┐
│                          Capability Contract                          │
│   (ServiceHandle, CapabilityDescriptor, PermissionRequirement)        │
└──────────────────────────────────────────────────────────────────────┘
              ▲                    ▲                      ▲
              │                    │                      │
   ┌──────────┴─────────┐  ┌───────┴────────┐  ┌──────────┴─────────┐
   │  4.2 Broker        │  │ 4.3 Resilience │  │ 4.4 Fallback       │
   │  (owns lifecycle,  │  │ (health, retry,│  │ (degraded paths,   │
   │   arbitration,     │  │  backoff,      │  │  capability        │
   │   gating)          │  │  circuit break)│  │  negotiation)      │
   └────────────────────┘  └────────────────┘  └────────────────────┘
```

| Module | Owns | Never Owns |
|--------|------|------------|
| **Broker** | Single-writer lifecycle state, observer registry, acquisition/release | Retry policy details, degraded-path logic |
| **Resilience** | Health probes, retry/backoff, circuit breaker state | Owning the service reference itself |
| **Fallback** | Capability-tier negotiation, substitute implementations | Deciding *whether* a capability is required |

---

## 4.2 Broker: Service Lifecycle Ownership

The Broker is the **only** component allowed to hold a live reference to a privileged service (e.g., an accessibility service, an injection bridge, a capture source). Every other module interacts through a `ServiceHandle`.

### 4.2.1 Lifecycle State Machine

The Broker models each capability as an explicit state machine. Transitions are driven only by platform callbacks (connect/disconnect) and Broker-initiated acquisition requests.

```
   ┌────────┐  acquire()   ┌───────────┐  onConnected()  ┌──────────┐
   │  IDLE  │─────────────▶│ ACQUIRING │────────────────▶│  READY   │
   └────────┘              └───────────┘                 └──────────┘
       ▲                        │  timeout / error             │
       │                        ▼                              │ onDisconnected()
       │                   ┌─────────┐                         │ / health-fail
       │                   │ FAILED  │◀────────────────────────┘
       │                   └─────────┘
       │                        │
       └────────────────────────┘  release() / reset()
```

| State | Meaning | Valid Operations |
|-------|---------|------------------|
| `IDLE` | No reference; service not requested | `acquire` |
| `ACQUIRING` | Requested; awaiting platform connect callback | `cancel`, timeout |
| `READY` | Live, validated handle held | `invoke`, `release` |
| `FAILED` | Could not acquire or lost connection unexpectedly | `reset`, delegate to Resilience |

### 4.2.2 Singleton Access & Volatility

The Broker holds the active service reference in a **volatile** field, because reads and writes occur across the platform's binder/lifecycle threads and the macro execution thread. Double-checked locking is the canonical acquisition pattern:

```java
public final class ServiceBroker<H extends ServiceHandle> {
    private static volatile ServiceBroker<?> active;
    private final AtomicReference<LifecycleState> state =
        new AtomicReference<>(LifecycleState.IDLE);
    private volatile H handle; // published on connect, nulled on disconnect

    public static <H extends ServiceHandle> ServiceBroker<H> get() { ... }

    // Called ONLY from platform connect callback thread.
    void onPlatformConnected(H connected) {
        this.handle = connected;
        state.set(LifecycleState.READY);
        notifyObservers(Event.CONNECTED);
    }

    // Called ONLY from platform disconnect callback thread.
    void onPlatformDisconnected() {
        this.handle = null;          // null before state flip
        state.set(LifecycleState.FAILED);
        notifyObservers(Event.DISCONNECTED);
    }
}
```

**Ordering contract:** on disconnect, the handle is invalidated *before* the state transition and observer notification, so no observer can dereference a stale handle. On connect, the handle is published *before* the state flip.

### 4.2.3 Observer Registry

Consumers subscribe to lifecycle transitions rather than polling state. The registry is a copy-on-write list to allow observers to (de)register during notification without concurrent-modification hazards.

```java
public interface LifecycleObserver {
    void onConnected(ServiceHandle handle);
    void onDisconnected(CapabilityDescriptor degraded);
    void onFailed(FailureReason reason);
}

// Broker maintains: CopyOnWriteArrayList<LifecycleObserver>
private void notifyObservers(Event e) {
    for (LifecycleObserver o : observers) {   // snapshot iteration
        try { o.dispatch(e); }
        catch (RuntimeException ex) { log.warn("observer threw", ex); } // isolate
    }
}
```

**Rule:** an observer exception must never abort notification of subsequent observers, nor propagate to the platform callback that triggered it.

### 4.2.4 Reference Discipline

- Handles are **opaque** and **non-transferable**; consumers receive them via callback, never construct them.
- Handles expose `isValid()` which checks the Broker's current state AND a per-handle generation/epoch counter, preventing use of a stale handle after reconnect.
- `release()` is idempotent; the Broker transitions `READY → IDLE` at most once per epoch.

```java
public interface ServiceHandle {
    long epoch();                 // monotonically increasing per connection
    boolean isValid();            // epoch matches live broker epoch && state == READY
    <R> R invoke(CapabilityOp<R> op) throws CapabilityException;
}
```

---

## 4.3 Resilience: Health, Retry, and Circuit Breaking

Resilience wraps Broker access with a health and recovery layer. It **does not** own the service reference; it observes Broker events and issues `acquire`/`reset` requests according to policy.

### 4.3.1 Health Probes

After a handle reaches `READY`, Resilience runs a lightweight **probe** to confirm the capability is genuinely usable (not merely connected). Probe strategies are pluggable:

| Probe Type | When Used | Cost |
|------------|-----------|------|
| `NoOpProbe` | Capability has a self-evident connect signal | Zero |
| `RoundTripProbe` | A cheap no-side-effect round-trip exists | Low |
| `SyntheticProbe` | Requires a controlled synthetic input/output | Medium |

Probes run on a dedicated health-executor, never on the macro execution thread, and are subject to a timeout declared by the capability descriptor.

### 4.3.2 Retry with Bounded Backoff

Acquisition retries are **exponential with jitter**, bounded by a maximum attempt count and a maximum wall-clock budget:

```java
RetryPolicy policy = RetryPolicy.builder()
    .baseDelay(Duration.ofMillis(200))
    .maxDelay(Duration.ofSeconds(5))
    .multiplier(2.0)
    .jitter(0.2)                 // ±20%
    .maxAttempts(5)
    .maxElapsed(Duration.ofSeconds(20))
    .build();
```

**Rules:**
- Retries are only attempted for `ACQUIRING → FAILED` transitions classified as *transient* (see 4.3.3).
- A retry sequence is cancelled immediately if the capability becomes `READY` out-of-band (platform connect fires late).
- Retry sequences are **per-capability**, not global; one failing capability must not throttle another.

### 4.3.3 Failure Classification

Every failure is classified so that Resilience and Fallback can act deterministically:

| Class | Examples | Retry? | Fallback? |
|-------|----------|--------|-----------|
| `TRANSIENT` | Timeout, temporary binder death | Yes (bounded) | Only after budget exhausted |
| `PERMANENT` | Capability unsupported on this API level | No | Yes (immediately) |
| `PERMISSION_DENIED` | User revoked grant | No | Only if a lower-permission tier exists |
| `CONFIGURATION` | Capability disabled in settings | No | Yes, with user-action surface |

Classification is a pure function of the platform error plus capability descriptor metadata; it is unit-testable and must not depend on runtime timing.

### 4.3.4 Circuit Breaker

To prevent hammering a persistently failing capability, Resilience maintains a **per-capability circuit breaker**:

```
CLOSED ──(N consecutive failures)──▶ OPEN
  ▲                                   │
  │                                   │ (cooldown elapses)
  │                                   ▼
  └──(probe succeeds)── HALF_OPEN ◀───┘
```

| State | Behavior |
|-------|----------|
| `CLOSED` | Pass-through; normal acquisition |
| `OPEN` | Reject immediate `acquire`; route to Fallback; return `CapabilityUnavailable` |
| `HALF_OPEN` | Allow a single trial acquisition/probe; success→`CLOSED`, failure→`OPEN` |

The breaker state is **observable** and surfaced to the macro layer so automations can branch on capability availability rather than crash.

---

## 4.4 Fallback: Degraded Paths & Capability Negotiation

Fallback defines **how a macro continues when the preferred capability is unavailable**. It is declarative: capabilities declare ordered tiers, and the Fallback resolver selects the highest viable tier at execution time.

### 4.4.1 Capability Tiers

A capability (e.g., *input injection*, *screen capture*, *multi-display routing*) declares an **ordered tier list**. Each tier is a concrete strategy with its own permission requirement and platform-gate.

```java
CapabilityDescriptor injection = CapabilityDescriptor.builder("input.inject")
    .tier(Tier.native(/* minApi */ 24, Permission.ACCESSIBILITY))
    .tier(Tier.privilegedBridge(Permission.PRIVILEGED, /* minApi */ 0))
    .tier(Tier.disabled())          // terminal: capability refused, not silently no-op'd
    .build();
```

**Ordering rule:** tiers are sorted by *preference* (most robust / least privileged first if policy demands). The resolver selects the first tier whose `PermissionRequirement` is satisfied AND whose platform gate passes.

### 4.4.2 Runtime Negotiation

Resolution happens at **acquisition time**, not definition time, because permissions and API levels only matter at runtime:

```
resolve(capability) →
    for tier in capability.tiers (in preference order):
        if tier.apiGatePasses() and tier.permissionGranted():
            try acquire via Broker on tier → READY: return handle
        (on failure: continue to next tier, record FYI in diagnostics)
    return CapabilityUnavailable(reason = lastFailure)
```

Negotiation results are cached per **epoch** of the platform state (install/uninstall, permission grant/revoke, API not changing at runtime) and invalidated on permission-change broadcasts.

### 4.4.3 Terminal vs. Degraded Tiers

A tier is either:

- **Degraded-but-functional** — operations still succeed, possibly with different characteristics (e.g., a slower manual injection path vs. a native dispatch). The macro must not need to know; the handle presents the same `CapabilityOp` contract.
- **Terminal (`disabled`)** — the operation cannot be performed. This is surfaced as a typed error, **never** silently swallowed. Silently no-oping an input injection is a correctness bug, not a graceful degradation.

### 4.4.4 Fallback Coordination with Resilience

The two modules interact through a narrow interface:

```
Resilience.onCircuitOpen(capability)  ──▶ Fallback.invalidateTier(capability, activeTier)
Fallback.resolve(capability)          ──▶ Resilience.resetBreaker(capability) [on new tier]
```

The rule: **activating a lower tier resets the circuit breaker** for that capability, because the failure was tied to the higher tier, not the capability itself.

---

## 4.5 Permission Model

Permissions are a first-class, declarative property of every capability tier. The Broker refuses to acquire a tier whose permission requirement is unmet; Fallback skips it.

### 4.5.1 Permission Types

| Permission Kind | Example | Declaration | Enforcement |
|-----------------|---------|-------------|-------------|
| `SERVICE` | Accessibility service enabled | Declared by capability | Broker checks platform enabled-state |
| `PRIVILEGED` | System-signed bridge | Declared by capability | Broker verifies signature/availability |
| `RUNTIME` | OS-granted permission | Declared by capability | Fallback filters tier if not granted |
| `CONFIG` | User setting prerequisite | Declared by capability | Checked at resolve time |

### 4.5.2 Declaration & Query

```java
public interface PermissionRequirement {
    String id();
    PermissionState check(PermissionContext ctx);   // GRANTED / DENIED / UNKNOWN
    Intent requestIntent(PermissionContext ctx);    // nullable if not user-requestable
}
```

- `check` is side-effect-free and cacheable within an epoch.
- `requestIntent` returns null when the permission cannot be requested programmatically (e.g., system signature); in that case Fallback degrades to a lower tier or terminal.

### 4.5.3 Permission-Change Reactivity

The Broker subscribes to permission-state broadcasts and, on change:

1. Invalidates the negotiation cache for affected capabilities.
2. Transitions `READY` handles whose tier's permission was revoked to `FAILED`.
3. Re-runs Fallback resolution to select a still-valid tier.
4. Notifies observers so macros can surface a user-visible prompt.

---

## 4.6 Cross-Cutting Contracts

### 4.6.1 Threading Contract

| Concern | Rule |
|---------|------|
| Platform callbacks | Invoked on platform threads; Broker methods called from callbacks must be lock-free and non-blocking |
| Observer notification | Snapshot iteration; exceptions isolated; never block platform thread |
| Health probes / retries | Dedicated executor; never on macro execution thread |
| Handle `invoke` | Timeout-bounded; propagates typed `CapabilityException` |

### 4.6.2 Testing Hooks

Each module exposes seams for deterministic testing:

- **Broker**: injectable fake platform-callback source (`onPlatformConnected`/`onPlatformDisconnected` drive state transitions in tests).
- **Resilience**: injectable clock and scheduler so backoff and breaker cooldown are testable without wall-clock waits.
- **Fallback**: injectable `PermissionContext` and API-level provider so tier negotiation is a pure function of inputs.

### 4.6.3 Diagnostics Surface

The lifecycle subsystem emits a structured diagnostics stream (never blocking):

```
{ capability, state, activeTier, tierIndex, lastFailureClass, breakerState, epoch }
```

This stream is the sole source of truth for user-facing "why did my macro degrade?" explanations and is consumed by the macro layer to branch on availability.

---

## 4.7 Module Interaction Summary

```
                     ┌───────────────────────────┐
   macro step ──────▶│  CapabilityHandle (opaque) │
                     └──────────────┬────────────┘
                                    │ invoke(CapabilityOp)
                                    ▼
                     ┌───────────────────────────┐
                     │        serviceBroker       │◀── platform connect/disconnect
                     │  (lifecycle + arbitration) │
                     └──────────┬─────────────────┘
                                │ acquire / release
                    ┌───────────┴────────────┐
                    ▼                        ▼
        ┌────────────────────┐   ┌────────────────────┐
        │    Resilience      │   │     Fallback       │
        │  probes / retry /  │◀─▶│  tier negotiation  │
        │  circuit breaker   │   │  & permission gate │
        └────────────────────┘   └────────────────────┘
                    │                        │
                    └──────────┬─────────────┘
                               ▼
                 ┌───────────────────────────┐
                 │  PermissionRequirement     │
                 │  & platform capability     │
                 │  descriptors (declarative) │
                 └───────────────────────────┘
```

**Invariants enforced across the subsystem:**

1. No caller outside the Broker ever holds a direct platform service reference.
2. A handle is valid only in `READY` and only for its creation epoch.
3. Every unavailable capability resolves to a typed error or a declared degraded tier — never a silent no-op.
4. All retry, backoff, and probe behavior is bounded and cancelable.
5. Permission revocation deterministically transitions dependent handles to `FAILED` and triggers re-negotiation.


## 5. Data Flow: Macro Execution Lifecycle & API/OS Integration Summary

# 5. Data Flow: Macro Execution Lifecycle & API/OS Integration Summary

This section describes how a macro moves from authoring to execution, how individual statements resolve their arguments and produce effects, and how the engine bridges to platform APIs. The model is generalized so it applies regardless of the concrete statement set (touch injection, key injection, capture, etc.). All examples use generic terminology; gesture-specific and game-specific details are deliberately omitted.

---

## 5.1 High-Level Lifecycle Overview

A macro progresses through four distinct phases:

```
┌────────────┐   ┌────────────┐   ┌──────────────┐   ┌──────────────┐
│ AUTHORING  │ → │ PERSISTENCE│ → │  SCHEDULING  │ → │  EXECUTION   │
│ (editor)   │   │ (serial.)  │   │ (dispatcher) │   │ (statement)  │
└────────────┘   └────────────┘   └──────────────┘   └──────────────┘
      │                │                 │                   │
  bind fields     write/read        enqueue &             evaluate,
  to expressions  field values      resolve deps          dispatch, await
```

1. **Authoring** — the user constructs a flow of statement nodes. Each configurable field is stored not as a concrete value but as a lazily-evaluated *expression handle*. This allows values to be computed at runtime (e.g., from variables, sensor reads, or arithmetic).
2. **Persistence** — the flow is serialized to durable storage. Field order is fixed; version metadata gates optional fields so that flows authored by older engine versions remain loadable.
3. **Scheduling** — at run time the engine walks the flow graph, evaluates expression handles against the current execution context, and decides when each statement's effect should fire.
4. **Execution** — a statement either completes synchronously or, for asynchronous effects (input injection, I/O, capture), returns a *pending* result and resolves later via a callback.

The rest of this section details each phase and the boundary to the platform.

---

## 5.2 Configuration Loading & Argument Resolution

### 5.2.1 Deserialization

Each statement implements read/write/visit methods that traverse its fields in a **fixed order**. Optional or version-gated fields are read conditionally:

```text
read(reader):
    field_a   = reader.readExpression()
    field_b   = reader.readExpression()
    ...
    if (reader.versionCode >= INTRODUCED_VERSION):
        optional_field = reader.readExpression()
```

This guarantees **backward compatibility**: a flow saved before a field existed simply omits it, and the engine applies a default.

### 5.2.2 Expression Evaluation

At execution time, every field handle is evaluated against the current execution context. Type coercion is explicit per field:

| Handle Kind | Resolves To | Typical Use |
|-------------|-------------|-------------|
| numeric expression | `Double` / `int` / `float` | coordinates, durations, speeds |
| boolean expression | `Boolean` | flags, branches |
| string / object expression | `String` / arbitrary | labels, identifiers |

### 5.2.3 Normalization Pipeline

Raw field values are rarely passed directly to the platform. A **normalization pipeline** converts authoring-space units into platform-space units:

```
raw value ──► clamp to valid range ──► scale by device metric ──► platform value
   (%)             [0..1]                (density / size)          (pixels, ms)
```

Examples of the pipeline:
- **Percentage → pixel**: divide by 100, clamp to `[0,1]`, multiply by display dimension.
- **Path length → duration**: `duration_ms = (path_length_px / (speed * density)) * 1000`.
- **Missing required argument**: throw a `RequiredArgumentNullException` and abort the statement (propagated as a flow error).

---

## 5.3 Execution Phases

### 5.3.1 Synchronous Completion

A statement that finishes its work immediately returns a success/failure result and the flow advances inline.

### 5.3.2 Asynchronous Completion (the common case for OS effects)

Statements that touch the OS — input injection, screen capture, I/O — cannot complete on the calling thread. These implement an **asynchronous statement contract**:

```text
enter(context):
    construct platform request
    hand request to platform / helper
    return PENDING                       // do NOT advance flow yet

on_success(result):
    finish(context, SUCCESS)

on_failure(reason):
    finish(context, FAILURE)
```

The engine's scheduler parks the flow at that node until the callback resolves it. This decoupling is critical because OS APIs (e.g., accessibility gesture dispatch) report completion on a separate handler/thread long after the call returns.

### 5.3.3 The Statement Execution Model

```
┌──────────────────────────────────────────────────────────┐
│  resolve arguments  →  normalize  →  build platform obj   │
└──────────────────────────────────────────────────────────┘
                          ▼
              ┌───────────┴───────────┐
       sync effect                 async effect
              ▼                       ▼
     finish(SUCCESS)          hand to OS + return PENDING
                                      ▼
                          on_success / on_failure callback
                                      ▼
                              finish(SUCCESS|FAILURE)
```

---

## 5.4 API / OS Integration Boundary

The engine never talks to raw hardware directly; it goes through **platform service privileged APIs**. The integration boundary is summarized below.

### 5.4.1 Capability Gating

Because platform APIs vary by OS version, the engine performs **runtime capability checks** before selecting an execution path:

```text
if (platform_version >= MODERN_APIS):     use native / privileged dispatch
else:                                     use legacy fallback (manual synthesis)
```

Documented gate points (generic):

| Gate | Meaning |
|------|---------|
| API 17 | display enumeration via `DisplayManager` vs `WindowManager` |
| API 24 | native gesture/input dispatch API availability |
| API 30 | multi-display routing for injected events |

### 5.4.2 Dual-Path Execution

For each privileged operation the engine may implement two paths:

- **Native path** (modern OS): Construct the platform request object (e.g., a gesture/event description) and hand it to the system service. Register a **result callback** to receive async completion.
- **Legacy fallback** (older OS): Synthesize events manually via a low-level injection bridge, driven by a scheduled ticker that interpolates between event timestamps and emits `down/move/up` sequences until the gesture duration elapses.

```
native:   build request ─► dispatch ─► callback ─► resolve
legacy:   build strokes ─► ticker loop:
              ├─ compute event boundaries in window
              ├─ emit down/up transitions
              ├─ emit move (interpolated) 
              └─ reschedule (capped interval)
            → resolve when no active pointers remain
```

### 5.4.3 Result Reporting

Async results are funneled back to the engine through a single completion function (`q2(Boolean)` in the source), which calls the statement's `x0(...)` completion handler. This uniform path means:
- Success and cancellation are distinguished by the boolean payload.
- The scheduler resumes the flow only from the completion handler, never from the dispatch call site.

### 5.4.4 Service Binding

Privileged operations require a bound system service (e.g., an accessibility service). The engine:
1. Holds a singleton reference to the active service instance.
2. Registers observers that are notified on connect/disconnect.
3. Declares required permissions per API level (e.g., a service permission on modern OS, a privileged signature-level permission on legacy OS).

```
engine ── register observer ──► service
service ── on_connected ──────► engine (dispatch ready)
service ── on_disconnected ───► engine (fail pending ops)
```

---

## 5.5 End-to-End Walkthrough

Putting it all together, a single statement's journey is:

```
1. AUTHOR      user configures fields → stored as expression handles
2. SAVE        serialize fields (fixed order, version-gated)
3. LOAD        deserialize → rebuild handles
4. DISPATCH    scheduler reaches node → invoke enter()
5. RESOLVE     evaluate each handle against execution context
6. NORMALIZE   %→pixel, length→duration, clamp, validate
7. BUILD       construct platform request object
8. HAND OFF    dispatch to OS (native) or ticker (legacy)
                          │
9. AWAIT       return PENDING; scheduler parks the flow
                          │
10. CALLBACK   OS reports success/cancel → completion handler
11. RESUME     finish(SUCCESS|FAILURE) → flow advances
```

---

## 5.6 Key Design Invariants

1. **Asynchrony is mandatory** for OS effects — the dispatch call site never blocks and never signals completion.
2. **Argument normalization precedes platform contact** — values are always in platform units before dispatch.
3. **Capability gating is runtime, not compile-time** — the same flow runs on old and new OS versions via path selection.
4. **Version-gated serialization** — new fields never break old flows.
5. **Single completion channel** — all async outcomes funnel through one callback mechanism, guaranteeing the scheduler has exactly one resume point per statement.
6. **Output-only vs input-observing operations are separated** — injection statements emit effects and depend only on the OS service; observation/capture lives in distinct statement types. The lifecycle model is identical for both, differing only in which service API is invoked at the HAND OFF stage.

---

## 5.7 Integration Summary Table

| Concern | Engine Role | Platform Boundary |
|---------|-------------|-------------------|
| Coordinates | clamp + scale | display metrics service |
| Timing | duration computation | system timing constants |
| Injection | build + dispatch request | privileged service (native) / injection bridge (legacy) |
| Completion | park + resume flow | OS result callback |
| Permissions | declare requirements | service permission system |
| Display routing | select target display | display manager (modern) / window manager (legacy) |
| Multi-path | gate on OS version | `SDK_INT`-style capability check |


## 6. Next Steps for Implementation

# 6. Next Steps for Implementation

This section outlines the concrete work required to turn the preceding architectural analysis into a working, testable macro engine. The ordering below follows dependency order: capability probes must come first, since every later layer branches on them.

## 6.1 Capability Probing and Runtime Feature Flags

Before any input injection code is written, the engine must establish *what it is allowed to do and how* on the host device. The source material shows two distinct injection strategies gated by platform version and privilege:

| Strategy | Precondition | Mechanism |
|---|---|---|
| Native gesture dispatch | API 24+ with an AccessibilityService | `dispatchGesture()` on a registered service |
| Manual `MotionEvent` synthesis | Pre-API-24, or lacking service | `injectInputEvent` / privileged bridge with interpolated events |
| Privileged injection | Host is system-signed | `ACCESS_PRIVILEGED`-style permission grant |

**Action items:**

- [ ] Implement a `CapabilityProbe` module returning a structured descriptor:
  ```text
  CapabilityReport {
      sdkInt,
      hasAccessibilityService,
      canDispatchGesture,     // sdkInt >= 24 && service != null
      canInjectPrivileged,    // privileged permission granted
      supportsMultiDisplay,   // sdkInt >= 30
      displayManagerApi17,    // sdkInt >= 17 -> DisplayManager, else WindowManager
  }
  ```
- [ ] Define feature gates (`FEATURE_NATIVE_GESTURE`, `FEATURE_LEGACY_INPUT`, `FEATURE_MULTI_DISPLAY`) resolved once at startup and cached.
- [ ] Emit a clear diagnostic + fallback path when **no** injection strategy is available, rather than failing at gesture-emit time.

## 6.2 Abstract the Input-Injection Strategy

The `Strategy`-pattern split between native dispatch and manual synthesis should be reflected directly in the engine's code layout. Do not let platform checks (`if sdkInt >= 24`) leak into gesture-construction logic.

**Action items:**

- [ ] Define an `InputInjector` interface:
  ```text
  interface InputInjector {
      // Returns a future/handle; resolves on completion or cancellation.
      InjectionResult inject(GesturePlan plan)
  }
  ```
- [ ] Implement two concrete injectors:
  - `NativeGestureInjector` — builds a `GestureDescription` (with `setDisplayId` when API ≥ 30), dispatches, and resolves via a `GestureResultCallback` equivalent.
  - `SyntheticEventInjector` — the ticker-based loop that interpolates pointers along paths and emits `DOWN`/`MOVE`/`UP` events on a schedule.
- [ ] Provide an `InjectorFactory` that selects the injector from the `CapabilityReport`.
- [ ] Ensure the **gesture plan is injector-agnostic**: a plan is a list of strokes `{path, startTime, endTime}`, with all geometry computed before injection.

## 6.3 Geometry and Timing Subsystem (Port First, Test Heavily)

The pure computational pieces are the highest-risk area for silent bugs (off-by-one interpolation, wrong units) and the easiest to unit-test in isolation. Port them before wiring up any platform callback.

**Action items:**

- [ ] `CoordinateResolver` — read normalized (percent) fields, clamp to `[0,1]`, throw a typed `MissingArgumentError` on null; scale by real display size.
- [ ] `DurationCalculator` — `length / (speed * density) * 1000` ms, with explicit validation that `speed > 0` (throw domain-specific error, not a bare `IllegalArgumentException`).
- [ ] `GestureBuilders` — one pure function per gesture type (tap, long-press, double-tap, swipe, pinch-in, pinch-out), each returning a `GesturePlan`. Pinch builders must create two symmetric strokes converging/diverging from the midpoint.
- [ ] `PathSampler` — given a stroke and a normalized time `t ∈ [0,1]`, return `(x, y)` via `PathMeasure.getPosTan()`. This is the shared core of both the legacy ticker and any preview/debug overlay.
- [ ] Pull platform timing constants (`tapTimeout`, `longPressTimeout`, `doubleTapTimeout`) through a small `TimingConstants` abstraction so tests can inject deterministic values.

> **Testing note:** every function in this subsystem should be a pure function over primitives. Write table-driven tests covering clamp boundaries (`t = 0`, `t = 1`, out-of-range), zero-length paths, and degenerate pinches (start == end).

## 6.4 Display Resolution Layer

The source material shows three distinct display-lookup paths (API ≥ 17 via `DisplayManager`, API < 17 via `WindowManager`, and multi-display routing via `setDisplayId` on API ≥ 30).

**Action items:**

- [ ] `DisplayResolver` returning `{realWidth, realHeight, density, displayId}`.
- [ ] Version-gate the lookup internally; expose one stable signature to callers.
- [ ] Plumb `displayId` through the gesture plan only when `supportsMultiDisplay`; otherwise ignore/default.
- [ ] Add a boundary test for density scaling — verify a "50% of screen" gesture lands at `width * 0.5` regardless of display metrics.

## 6.5 Legacy Ticker Loop (If Pre-API-24 Support Is In Scope)

The synthetic-event path is the most intricate component: a re-scheduled loop that computes event boundaries, emits pointer transitions, interpolates `MOVE` events, and terminates when no active pointers remain.

**Action items:**

- [ ] Implement the loop as a state machine:
  ```
  state: { activePointers, pendingStrokes, lastTimestamp, downTime }
  tick(now):
      nextEnd   = earliest stroke end   in (lastTimestamp, now]
      nextStart = earliest stroke start in (lastTimestamp, now]
      process ends   -> POINTER_UP / UP, pressure = 0
      process starts -> POINTER_DOWN / DOWN
      emit MOVE interpolating all active pointers to `now`
      if activePointers empty -> complete
      else reschedule at min(nextBoundary, now + 100ms)
  ```
- [ ] Reuse `PointerCoords` / `PointerProperties` buffers rather than allocating per tick (allocation churn is a common cause of jank in high-frequency injection).
- [ ] Add a strict cap on total gesture duration; reject plans exceeding the platform maximum **before** scheduling.
- [ ] **Decision gate:** if pre-API-24 devices are out of scope for v1, stub the injector with an explicit `UnsupportedPlatform` result and defer this work — but keep the interface in place.

## 6.6 Async Completion and Result Propagation

Both injection paths complete asynchronously (native callback or ticker termination), and the macro engine must not block the flow.

**Action items:**

- [ ] Define a single `CompletionCallback { onSuccess() | onCancelled(reason) }` contract shared by all injectors.
- [ ] Guarantee exactly-once completion, even on service disconnection mid-gesture.
- [ ] On cancellation, surface a distinct result code (`Cancelled`) rather than silently reporting success, so flows can handle it.
- [ ] Wire the completion into the engine's scheduling context so the next statement resumes only after resolution.

## 6.7 Observable Service Lifecycle

Gesture dispatch depends on a live `AccessibilityService`. The service is a singleton that connects/disconnects and notifies observers.

**Action items:**

- [ ] Implement a service registry with an observer list and a thread-safe active-instance reference (`volatile` or equivalent; double-checked locking is acceptable but a `std::atomic` / lock-free pointer is preferred in the target language).
- [ ] Subscribe the `InputInjector` to connect/disconnect events:
  - On **connect**: enable native injection.
  - On **disconnect**: mark injector unavailable, cancel in-flight gestures as `Cancelled`, and downgrade capability flags.
- [ ] Provide a queryable `isServiceConnected()` used by the capability probe at runtime (not just startup).

## 6.8 Serialization and Backward Compatibility

Statements are persisted and reloaded across engine versions. The source material demonstrates version-gated field reading (e.g., reading a newer field only when the stored version code is high enough).

**Action items:**

- [ ] Implement a versioned reader/writer for each statement type.
- [ ] Enforce the **read order contract**; add a schema version constant and bump on any field addition.
- [ ] For any new field: on read, gate by post-bump version and default sensibly for older records.
- [ ] Add round-trip tests: write(record) → read(record) → deep-equal, plus a "load an old-version record" fixture that omits newer fields.

## 6.9 Permission Model

The engine must declare which permissions it needs *conditionally* on platform version (accessibility service on API ≥ 24 vs. a privileged permission otherwise).

**Action items:**

- [ ] Implement `declaredPermissions()` returning the set appropriate to the current platform.
- [ ] At flow-start, verify the required permission is actually *granted* (not merely declared) and fail fast with an actionable message pointing the user to the settings screen.
- [ ] Document the privileged-signing requirement for the legacy path prominently; most devices will not satisfy it.

## 6.10 Suggested Milestone Sequence

| Milestone | Scope | Exit Criteria |
|---|---|---|
| **M1 — Geometry Core** | `CoordinateResolver`, `DurationCalculator`, `GestureBuilders`, `PathSampler` | 100% unit-test coverage; all gesture types produce expected `GesturePlan`s on synthetic metrics |
| **M2 — Native Injection** | `CapabilityProbe`, `DisplayResolver`, `NativeGestureInjector`, service registry | End-to-end tap/swipe on API 24+ device; cancellation propagates correctly |
| **M3 — Persistence** | Versioned read/write for all statements | Round-trip + legacy-fixture tests pass |
| **M4 — Legacy Path** *(optional v1)* | `SyntheticEventInjector` ticker loop | Tap/swipe verified on emulator at API 23; duration cap enforced |
| **M5 — Hardening** | Permission checks, disconnect handling, multi-display gating | Failure modes produce actionable errors; no silent successes |

## 6.11 Open Questions to Resolve Before Coding

- [ ] **Scope of legacy support:** Is pre-API-24 injection a hard requirement for v1, or can it ship as a documented gap with a clean `UnsupportedPlatform` error?
- [ ] **Target language & concurrency model:** The completion/ticker design assumes a scheduled executor; confirm the target runtime (threads vs. event loop) and choose the observer/callback primitives accordingly.
- [ ] **Determinism in tests:** Confirm that timing constants can be injected so the ticker loop is testable without real wall-clock waits.
- [ ] **Multi-display:** Is `displayId` routing needed for v1, or defer behind a feature gate like the legacy path?

Answering these four questions determines whether M4 and the multi-display portions of M2 expand or collapse before development begins.

