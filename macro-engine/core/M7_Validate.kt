// M7a: static validation pipeline for the macro DSL IR.
// Pure Kotlin, no Android imports. Reuses M6_Dsl.kt declarations (Span, Op & nested,
// TargetDecl, Meta, FlowIr) — we must NOT redeclare them here.

package com.macroengine.dsl

// Faults and report -----------------------------------------------------------

// A single validation fault (error or warning) anchored to a source span.
data class ValidationFault(
    val code: String,
    val message: String,
    val span: Span
)

// Full validation output. ok == errors.isEmpty().
data class ValidationReport(
    val errors: List<ValidationFault>,
    val warnings: List<ValidationFault>
) {
    val ok: Boolean = errors.isEmpty()
}

// Insertion-ordered mutable accumulator used while validating.
private class Collector {
    val errors = ArrayList<ValidationFault>()
    val warnings = ArrayList<ValidationFault>()
    fun err(code: String, message: String, span: Span) {
        errors.add(ValidationFault(code, message, span))
    }
    fun warn(code: String, message: String, span: Span) {
        warnings.add(ValidationFault(code, message, span))
    }
}

// Small assertion helpers used only by the embedded test harness.
private fun assertTrue(actual: Boolean, label: String) {
    if (!actual) throw IllegalStateException("assertTrue failed: " + label)
}

private fun assertEq(expected: Any?, actual: Any?, label: String) {
    if (expected != actual) {
        throw IllegalStateException(
            "assertEq failed: " + label + " expected=" + expected + " actual=" + actual
        )
    }
}

// Tiny check runner: runs a block, reports PASS/FAIL, rethrows nothing.
private fun check(name: String, block: () -> Unit) {
    try {
        block()
        println("PASS " + name)
    } catch (t: Throwable) {
        println("FAIL " + name + " :: " + t.message)
        throw t
    }
}

// Known action types with their selector-arity requirements.
// selectorCount: -1 means "unknown/not a selector-taking action" -> not applicable.
private val SELECTOR_ACTIONS = setOf(
    "tap", "long_press", "double_tap", "wait_for", "wait_until", "swipe"
)

// Signature table: name -> required selector arg count rules.
// tap/long_press/double_tap/wait_for/wait_until = exactly 1 selector arg.
// swipe >= 1. log = exactly 1 (message) arg.
private fun expectedSelectorArity(call: String): Int {
    // returns: -1 unknown, -2 "at least one", otherwise exact count for selectors
    return when (call) {
        "tap", "long_press", "double_tap", "wait_for", "wait_until" -> 1
        "swipe" -> -2
        else -> -1
    }
}

// Command names that may NOT appear as the leading token of an If cond.
private val COMMAND_NAMES = setOf(
    "tap", "long_press", "double_tap", "wait_for", "wait_until", "swipe",
    "log", "return", "break", "continue", "sleep", "call"
)

// Selector kinds that require a specific capability to PREFLIGHT.
private fun capabilityForKind(kind: String): String? = when (kind) {
    "img", "text", "region" -> "SCREEN_CAPTURE"
    "ocr" -> "OCR"
    else -> null
}

private fun capabilityForAction(call: String): String? = when (call) {
    "tap", "swipe", "long_press", "double_tap" -> "INPUT_INJECT"
    else -> null
}

// Try to extract a selector name from an Action arg token.
// Accepts either a bare name ("btn_login") or "kind:name".
// Returns the name portion (or null if it looks like a plain literal / empty).
private fun selectorNameOf(token: String): String? {
    val t = token.trim()
    if (t.isEmpty()) return null
    val colon = t.indexOf(':')
    if (colon >= 0) {
        val name = t.substring(colon + 1).trim()
        return if (name.isEmpty()) null else name
    }
    // Bare token: only treat as selector name if it matches an identifier shape
    // (letters/digits/underscore, leading letter/underscore). Otherwise it's a literal.
    return if (isIdentifier(t)) t else null
}

private fun selectorKindOf(token: String): String? {
    val t = token.trim()
    val colon = t.indexOf(':')
    if (colon < 0) return null
    val kind = t.substring(0, colon).trim()
    return if (kind.isEmpty()) null else kind
}

private fun isIdentifier(s: String): Boolean {
    if (s.isEmpty()) return false
    val c0 = s[0]
    if (!(c0 == '_' || c0.isLetter())) return false
    var i = 1
    while (i < s.length) {
        val c = s[i]
        if (!(c == '_' || c.isLetterOrDigit())) return false
        i++
    }
    return true
}

// Main validation entry point --------------------------------------------------

fun validate(ir: FlowIr, grantedCaps: Set<String>): ValidationReport {
    val col = Collector()

    // Collected metadata used across phases.
    val subKeys: Set<String> = ir.subs.keys
    val targetByName: Map<String, TargetDecl> =
        ir.targets.associateBy { it.name }

    // Phase 1: RESOLVE --------------------------------------------------------
    // 1a. Dup loop labels.
    val labelCounts = LinkedHashMap<String, Int>()
    collectLoopLabels(ir.body, labelCounts)
    for ((label, count) in labelCounts) {
        // if key present with count > 1 we computed first-span separately
    }
    // Detect duplicates (need span: recompute first occurrence span).
    reportDupLabels(ir.body, labelCounts, col)

    // 1b. Op.Call.ref binds to subs; action selector args bind to targets.
    walkResolve(ir.body, subKeys, targetByName, col)
    // Also walk each sub body.
    for ((subName, subBody) in ir.subs) {
        walkResolve(subBody, subKeys, targetByName, col)
    }

    // Phase 2: TYPECHECK ------------------------------------------------------
    walkTypecheck(ir.body, col)
    for ((_, subBody) in ir.subs) {
        walkTypecheck(subBody, col)
    }

    // Phase 3: CFG ------------------------------------------------------------
    walkCfg(ir.body, insideLoop = false, col)
    for ((_, subBody) in ir.subs) {
        walkCfg(subBody, insideLoop = false, col)
    }
    // Sub call graph acyclicity.
    checkSubCycles(ir, col)

    // Phase 4: CAPABILITY PREFLIGHT (warnings) --------------------------------
    walkCapabilities(ir.body, targetByName, grantedCaps, col)
    for ((_, subBody) in ir.subs) {
        walkCapabilities(subBody, targetByName, grantedCaps, col)
    }

    // Phase 5: RESOURCE BUDGET (warnings) -------------------------------------
    // Unbounded loops.
    walkUnboundedLoops(ir.body, col)
    for ((_, subBody) in ir.subs) {
        walkUnboundedLoops(subBody, col)
    }
    // Flow timeout.
    if (ir.meta.timeoutMs <= 0L) {
        // Meta has no span of its own; anchor to a zero span. Use ir body first span
        // if available, else a synthetic Span. We cannot construct Span without knowing
        // its ctor; use the first target/op span when possible.
        val anchor = firstSpan(ir)
        col.warn("NO_FLOW_TIMEOUT", "meta.timeoutMs is not positive", anchor)
    }
    // Nested max product > 100000.
    val product = nestedMaxProduct(ir.body)
    if (product != null && product > 100000L) {
        val anchor = firstSpan(ir)
        col.warn(
            "BUDGET_CEILING",
            "nested maxIter product " + product + " exceeds 100000",
            anchor
        )
    }

    return ValidationReport(col.errors, col.warnings)
}

// Helper: find an anchor span for meta-level warnings.
private fun firstSpan(ir: FlowIr): Span {
    // Prefer first target span, then first op span, else first sub op span.
    if (ir.targets.isNotEmpty()) return ir.targets[0].span
    val b = firstOpSpan(ir.body)
    if (b != null) return b
    for ((_, subBody) in ir.subs) {
        val s = firstOpSpan(subBody)
        if (s != null) return s
    }
    // Last resort: derive from a target-less meta via a guaranteed-existing span.
    // We cannot fabricate a Span without its constructor; if nothing exists, use
    // the first sub's key ordering — but FlowIr requires a Span somewhere in practice.
    // Constructing one is impossible from here without the ctor; throw to surface
    // this in tests only when truly empty (tests always include at least one op).
    throw IllegalStateException("no span available in IR to anchor warning")
}

private fun firstOpSpan(ops: List<Op>): Span? {
    for (op in ops) {
        when (op) {
            is Op.Call -> return op.span
            is Op.Loop -> {
                op.span.let { return it }
            }
            is Op.If -> return op.span
            is Op.Action -> return op.span
            is Op.Cond -> return op.span
            is Op.Break -> return op.span
            is Op.Continue -> return op.span
            is Op.Return -> return op.span
            is Op.Log -> return op.span
        }
    }
    return null
}

// Phase 1 helpers --------------------------------------------------------------

private fun collectLoopLabels(ops: List<Op>, acc: LinkedHashMap<String, Int>) {
    for (op in ops) {
        when (op) {
            is Op.Loop -> {
                val l = op.label
                if (l != null) {
                    acc[l] = (acc[l] ?: 0) + 1
                }
                collectLoopLabels(op.body, acc)
            }
            is Op.If -> {
                collectLoopLabels(op.thenBody, acc)
                collectLoopLabels(op.elseBody, acc)
            }
            is Op.Action -> {
                collectLoopLabels(op.onTimeout, acc)
                collectLoopLabels(op.onFail, acc)
            }
            else -> { /* leaf */ }
        }
    }
}

private fun reportDupLabels(
    ops: List<Op>,
    counts: Map<String, Int>,
    col: Collector
) {
    // Walk again to find first offending span per duplicated label.
    val reported = HashSet<String>()
    reportDupLabelsWalk(ops, counts, reported, col)
}

private fun reportDupLabelsWalk(
    ops: List<Op>,
    counts: Map<String, Int>,
    reported: MutableSet<String>,
    col: Collector
) {
    for (op in ops) {
        when (op) {
            is Op.Loop -> {
                val l = op.label
                if (l != null) {
                    val c = counts[l] ?: 0
                    if (c > 1 && !reported.contains(l)) {
                        reported.add(l)
                        col.err("DUP_LABEL", "duplicate loop label " + l, op.span)
                    }
                }
                reportDupLabelsWalk(op.body, counts, reported, col)
            }
            is Op.If -> {
                reportDupLabelsWalk(op.thenBody, counts, reported, col)
                reportDupLabelsWalk(op.elseBody, counts, reported, col)
            }
            is Op.Action -> {
                reportDupLabelsWalk(op.onTimeout, counts, reported, col)
                reportDupLabelsWalk(op.onFail, counts, reported, col)
            }
            else -> { /* leaf */ }
        }
    }
}

private fun walkResolve(
    ops: List<Op>,
    subKeys: Set<String>,
    targetByName: Map<String, TargetDecl>,
    col: Collector
) {
    for (op in ops) {
        when (op) {
            is Op.Call -> {
                if (!subKeys.contains(op.ref)) {
                    col.err("UNBOUND_SUB", "call to unknown sub " + op.ref, op.span)
                }
            }
            is Op.Action -> {
                if (SELECTOR_ACTIONS.contains(op.call)) {
                    for (arg in op.args) {
                        val name = selectorNameOf(arg)
                        if (name != null) {
                            if (!targetByName.containsKey(name)) {
                                col.err(
                                    "UNBOUND_TARGET",
                                    "selector " + name + " does not bind to a target",
                                    op.span
                                )
                            }
                        }
                    }
                }
                walkResolve(op.onTimeout, subKeys, targetByName, col)
                walkResolve(op.onFail, subKeys, targetByName, col)
            }
            is Op.Loop -> {
                walkResolve(op.body, subKeys, targetByName, col)
            }
            is Op.If -> {
                walkResolve(op.thenBody, subKeys, targetByName, col)
                walkResolve(op.elseBody, subKeys, targetByName, col)
            }
            else -> { /* leaves don't reference subs/targets via args */ }
        }
    }
}

// Phase 2 helpers --------------------------------------------------------------

private fun walkTypecheck(ops: List<Op>, col: Collector) {
    for (op in ops) {
        when (op) {
            is Op.Action -> {
                typecheckAction(op, col)
                walkTypecheck(op.onTimeout, col)
                walkTypecheck(op.onFail, col)
            }
            is Op.If -> {
                typecheckCondExpression(op.cond, op.span, col)
                walkTypecheck(op.thenBody, col)
                walkTypecheck(op.elseBody, col)
            }
            is Op.Loop -> {
                walkTypecheck(op.body, col)
            }
            else -> { /* leaves */ }
        }
    }
}

// Known action names that have arity rules we validate.
private val KNOWN_ACTIONS = setOf(
    "tap", "long_press", "double_tap", "wait_for", "wait_until", "swipe", "log"
)

private fun typecheckAction(op: Op.Action, col: Collector) {
    val name = op.call
    if (!KNOWN_ACTIONS.contains(name)) {
        // Unknown action — still allow if it's a sub call? No: Action.call is an
        // action verb, subs come through Op.Call.ref. So unknown here is a fault.
        col.err("UNKNOWN_ACTION", "unknown action " + name, op.span)
        return
    }
    val arityRule = expectedSelectorArity(name)
    when {
        arityRule == -1 && name == "log" -> {
            if (op.args.size != 1) {
                col.err(
                    "UNKNOWN_ACTION",  // arity violation for log reported as a fault
                    "log expects exactly 1 arg, got " + op.args.size,
                    op.span
                )
            }
        }
        arityRule == 1 -> {
            if (op.args.size != 1) {
                col.err(
                    "UNKNOWN_ACTION",
                    name + " expects exactly 1 selector arg, got " + op.args.size,
                    op.span
                )
            }
        }
        arityRule == -2 -> {
            if (op.args.size < 1) {
                col.err(
                    "UNKNOWN_ACTION",
                    name + " expects at least 1 selector arg, got " + op.args.size,
                    op.span
                )
            }
        }
        else -> { /* no arity rule */ }
    }
}

private fun typecheckCondExpression(cond: String, span: Span, col: Collector) {
    val t = cond.trim()
    if (t.isEmpty()) return
    // If cond begins with a command name (followed by space, '(', or end) => fault.
    val firstToken = firstTokenOf(t)
    if (firstToken != null && COMMAND_NAMES.contains(firstToken)) {
        col.err("CMD_AS_COND", "command used as condition: " + firstToken, span)
    }
}

private fun firstTokenOf(s: String): String? {
    val trimmed = s.trim()
    if (trimmed.isEmpty()) return null
    var i = 0
    while (i < trimmed.length) {
        val c = trimmed[i]
        if (c == ' ' || c == '\t' || c == '(' || c == '\n') break
        i++
    }
    return if (i == 0) null else trimmed.substring(0, i)
}

// Phase 3 helpers --------------------------------------------------------------

private fun walkCfg(ops: List<Op>, insideLoop: Boolean, col: Collector) {
    // Track whether a terminator occurred earlier in THIS block.
    var terminatedAfter: Span? = null
    for (op in ops) {
        if (terminatedAfter != null) {
            col.err(
                "UNREACHABLE",
                "statement after terminator at same block level",
                opSpan(op)
            )
            // Only report once per block after first terminator; keep scanning but
            // avoid spamming: mark as still unreachable.
            // (Do not reset terminatedAfter.)
        }
        when (op) {
            is Op.Break -> {
                if (!insideLoop) {
                    col.err("BREAK_OUTSIDE_LOOP", "break outside any loop", op.span)
                }
                if (terminatedAfter == null) terminatedAfter = op.span
            }
            is Op.Continue -> {
                if (!insideLoop) {
                    col.err("BREAK_OUTSIDE_LOOP", "continue outside any loop", op.span)
                }
                if (terminatedAfter == null) terminatedAfter = op.span
            }
            is Op.Return -> {
                if (terminatedAfter == null) terminatedAfter = op.span
            }
            is Op.Loop -> {
                walkCfg(op.body, insideLoop = true, col)
            }
            is Op.If -> {
                walkCfg(op.thenBody, insideLoop, col)
                walkCfg(op.elseBody, insideLoop, col)
            }
            is Op.Action -> {
                walkCfg(op.onTimeout, insideLoop, col)
                walkCfg(op.onFail, insideLoop, col)
            }
            else -> { /* leaves */ }
        }
    }
}

private fun opSpan(op: Op): Span = when (op) {
    is Op.Call -> op.span
    is Op.Loop -> op.span
    is Op.If -> op.span
    is Op.Action -> op.span
    is Op.Cond -> op.span
    is Op.Break -> op.span
    is Op.Continue -> op.span
    is Op.Return -> op.span
    is Op.Log -> op.span
}

// Build adjacency: sub -> list of sub refs called directly in that sub's body.
private fun checkSubCycles(ir: FlowIr, col: Collector) {
    val adj = HashMap<String, MutableList<String>>()
    for (key in ir.subs.keys) {
        adj[key] = ArrayList()
    }
    for ((subName, body) in ir.subs) {
        val refs = ArrayList<String>()
        collectCallRefs(body, refs)
        val out = adj[subName] ?: ArrayList()
        for (r in refs) {
            if (ir.subs.containsKey(r)) out.add(r)
        }
        adj[subName] = out
    }
    // DFS three-color cycle detection.
    val WHITE = 0
    val GRAY = 1
    val BLACK = 2
    val color = HashMap<String, Int>()
    for (k in adj.keys) color[k] = WHITE

    for (start in adj.keys.toList()) {
        if (color[start] == WHITE) {
            val cycleNode = dfsCycle(start, adj, color, GRAY, BLACK)
            if (cycleNode != null) {
                // Anchor the cycle fault to a sub body span.
                val span = spanOfSub(ir, cycleNode)
                col.err(
                    "SUB_CYCLE",
                    "sub call graph contains a cycle through " + cycleNode,
                    span
                )
                // Only report one fault per acyclic-check pass.
                return
            }
        }
    }
}

private fun dfsCycle(
    node: String,
    adj: Map<String, MutableList<String>>,
    color: MutableMap<String, Int>,
    gray: Int,
    black: Int
): String? {
    color[node] = gray
    val neighbors = adj[node] ?: emptyList<String>()
    for (n in neighbors) {
        val c = color[n] ?: 0
        if (c == gray) {
            return n
        }
        if (c == 0) {
            val found = dfsCycle(n, adj, color, gray, black)
            if (found != null) return found
        }
    }
    color[node] = black
    return null
}

private fun spanOfSub(ir: FlowIr, subName: String): Span {
    val body = ir.subs[subName]
    if (body != null) {
        val s = firstOpSpan(body)
        if (s != null) return s
    }
    return firstSpan(ir)
}

private fun collectCallRefs(ops: List<Op>, out: MutableList<String>) {
    for (op in ops) {
        when (op) {
            is Op.Call -> out.add(op.ref)
            is Op.Loop -> collectCallRefs(op.body, out)
            is Op.If -> {
                collectCallRefs(op.thenBody, out)
                collectCallRefs(op.elseBody, out)
            }
            is Op.Action -> {
                collectCallRefs(op.onTimeout, out)
                collectCallRefs(op.onFail, out)
            }
            else -> { /* leaf */ }
        }
    }
}

// Phase 4 helpers --------------------------------------------------------------

private fun walkCapabilities(
    ops: List<Op>,
    targetByName: Map<String, TargetDecl>,
    granted: Set<String>,
    col: Collector
) {
    for (op in ops) {
        when (op) {
            is Op.Action -> {
                // Action-level capability requirement.
                val actCap = capabilityForAction(op.call)
                if (actCap != null && !granted.contains(actCap)) {
                    col.warn(
                        "CAP_MISSING",
                        "action " + op.call + " requires " + actCap,
                        op.span
                    )
                }
                // Selector-kind requirements for selector-taking actions.
                if (SELECTOR_ACTIONS.contains(op.call)) {
                    for (arg in op.args) {
                        val name = selectorNameOf(arg)
                        if (name == null) continue
                        val kindA = selectorKindOf(arg)
                        var kindB: String? = null
                        if (kindA != null) {
                            kindB = kindA
                        } else {
                            val decl = targetByName[name]
                            if (decl != null) kindB = decl.kind
                        }
                        if (kindB != null) {
                            val needed = capabilityForKind(kindB)
                            if (needed != null && !granted.contains(needed)) {
                                col.warn(
                                    "CAP_MISSING",
                                    "selector kind " + kindB + " requires " + needed,
                                    op.span
                                )
                            }
                        } else {
                            // If we couldn't infer the kind but the target exists, use
                            // its declared kind.
                            val decl = targetByName[name]
                            if (decl != null) {
                                val needed = capabilityForKind(decl.kind)
                                if (needed != null && !granted.contains(needed)) {
                                    col.warn(
                                        "CAP_MISSING",
                                        "selector kind " + decl.kind + " requires " + needed,
                                        op.span
                                    )
                                }
                            }
                        }
                    }
                }
                walkCapabilities(op.onTimeout, targetByName, granted, col)
                walkCapabilities(op.onFail, targetByName, granted, col)
            }
            is Op.Loop -> walkCapabilities(op.body, targetByName, granted, col)
            is Op.If -> {
                walkCapabilities(op.thenBody, targetByName, granted, col)
                walkCapabilities(op.elseBody, targetByName, granted, col)
            }
            else -> { /* leaf */ }
        }
    }
}

// Phase 5 helpers --------------------------------------------------------------

private fun walkUnboundedLoops(ops: List<Op>, col: Collector) {
    for (op in ops) {
        when (op) {
            is Op.Loop -> {
                if (op.maxIter == null) {
                    col.warn("UNBOUNDED_LOOP", "loop has no maxIter bound", op.span)
                }
                walkUnboundedLoops(op.body, col)
            }
            is Op.If -> {
                walkUnboundedLoops(op.thenBody, col)
                walkUnboundedLoops(op.elseBody, col)
            }
            is Op.Action -> {
                walkUnboundedLoops(op.onTimeout, col)
                walkUnboundedLoops(op.onFail, col)
            }
            else -> { /* leaf */ }
        }
    }
}

// Compute the maximum possible "nested loop product" through a body.
// Returns null if no loops exist.
private fun nestedMaxProduct(ops: List<Op>): Long? {
    var maxAcrossSiblings: Long? = null
    for (op in ops) {
        val local = localProduct(op)
        if (local != null) {
            maxAcrossSiblings = if (maxAcrossSiblings == null) local
            else maxOf(maxAcrossSiblings, local)
        }
    }
    return maxAcrossSiblings
}

private fun localProduct(op: Op): Long? = when (op) {
    is Op.Loop -> {
        val childMax = nestedMaxProduct(op.body) ?: 1L
        // If this loop is unbounded, its product contribution is effectively its
        // maxIter (null treated as 1 for product, reported separately).
        val self: Long = op.maxIter?.toLong() ?: 1L
        val nested = safeMul(self, childMax)
        nested
    }
    is Op.If -> {
        val t = nestedMaxProduct(op.thenBody)
        val e = nestedMaxProduct(op.elseBody)
        when {
            t == null && e == null -> null
            t == null -> e
            e == null -> t
            else -> maxOf(t, e)
        }
    }
    is Op.Action -> {
        val t = nestedMaxProduct(op.onTimeout)
        val f = nestedMaxProduct(op.onFail)
        when {
            t == null && f == null -> null
            t == null -> f
            f == null -> t
            else -> maxOf(t, f)
        }
    }
    else -> null
}

// Saturating multiply to avoid overflow in budget computations.
private fun safeMul(a: Long, b: Long): Long {
    if (a == 0L || b == 0L) return 0L
    if (a > Long.MAX_VALUE / b) return Long.MAX_VALUE
    return a * b
}

// Embedded test harness --------------------------------------------------------

fun main() {
    // We need Span instances. Span's constructor is declared in M6_Dsl.kt and is
    // assumed to be Span(start: Int, end: Int) (typical). Adjust if M6 differs.
    val s0 = Span(0, 0)
    val s1 = Span(1, 1)
    val s2 = Span(2, 2)
    val s3 = Span(3, 3)
    val s4 = Span(4, 4)
    val s5 = Span(5, 5)
    val s6 = Span(6, 6)
    val s7 = Span(7, 7)
    val s8 = Span(8, 8)
    val s9 = Span(9, 9)
    val s10 = Span(10, 10)
    val s11 = Span(11, 11)

    val imgTarget = TargetDecl(
        name = "login_btn",
        kind = "img",
        value = "assert/login.png",
        confidence = 0.9,
        span = s0
    )

    val allCaps = setOf("SCREEN_CAPTURE", "OCR", "INPUT_INJECT")

    // Helper builders for tests.
    fun meta(timeoutMs: Long): Meta =
        Meta(name = "flow", timeoutMs = timeoutMs, requireCaps = ArrayList())

    fun ir(
        targets: List<TargetDecl>,
        body: List<Op>,
        subs: Map<String, List<Op>>,
        timeoutMs: Long
    ): FlowIr = FlowIr(
        irVersion = 1,
        flowId = "flow-1",
        meta = meta(timeoutMs),
        targets = targets,
        body = body,
        subs = subs
    )

    // Test: valid flow ok -----------------------------------------------------
    check("valid flow ok") {
        val body = listOf(
            Op.Loop(
                label = "main",
                everyMs = 100L,
                maxIter = 10,
                body = listOf(
                    Op.Action(
                        call = "tap",
                        args = listOf("login_btn"),
                        timeoutMs = null,
                        onTimeout = emptyList(),
                        onFail = emptyList(),
                        span = s2
                    )
                ),
                span = s1
            )
        )
        val flow = ir(listOf(imgTarget), body, emptyMap(), 30000L)
        val report = validate(flow, allCaps)
        assertTrue(report.ok, "valid ok")
        assertEq(0, report.warnings.size, "valid no warnings")
    }

    // Test: unbound sub --------------------------------------------------------
    check("unbound sub") {
        val body = listOf(Op.Call(ref = "missing_sub", span = s1))
        val flow = ir(listOf(imgTarget), body, emptyMap(), 30000L)
        val report = validate(flow, allCaps)
        assertTrue(!report.ok, "unbound sub not ok")
        assertTrue(
            report.errors.any { it.code == "UNBOUND_SUB" },
            "has UNBOUND_SUB"
        )
    }

    // Test: unbound target -----------------------------------------------------
    check("unbound target") {
        val body = listOf(
            Op.Action(
                call = "tap",
                args = listOf("does_not_exist"),
                timeoutMs = null,
                onTimeout = emptyList(),
                onFail = emptyList(),
                span = s3
            )
        )
        val flow = ir(listOf(imgTarget), body, emptyMap(), 30000L)
        val report = validate(flow, allCaps)
        assertTrue(
            report.errors.any { it.code == "UNBOUND_TARGET" && it.span == s3 },
            "has UNBOUND_TARGET at span"
        )
    }

    // Test: break outside loop ------------------------------------------------
    check("break outside loop") {
        val body = listOf(Op.Break(span = s4))
        val flow = ir(listOf(imgTarget), body, emptyMap(), 30000L)
        val report = validate(flow, allCaps)
        assertTrue(
            report.errors.any { it.code == "BREAK_OUTSIDE_LOOP" && it.span == s4 },
            "has BREAK_OUTSIDE_LOOP"
        )
    }

    // Test: unreachable after return ------------------------------------------
    check("unreachable after return") {
        val body = listOf(
            Op.Return(span = s5),
            Op.Log(msg = "dead", span = s6)
        )
        val flow = ir(listOf(imgTarget), body, emptyMap(), 30000L)
        val report = validate(flow, allCaps)
        assertTrue(
            report.errors.any { it.code == "UNREACHABLE" && it.span == s6 },
            "has UNREACHABLE at s6"
        )
    }

    // Test: sub cycle a -> b -> a ---------------------------------------------
    check("sub cycle a-b-a") {
        val subA = listOf(Op.Call(ref = "b", span = s7))
        val subB = listOf(Op.Call(ref = "a", span = s8))
        val subs = LinkedHashMap<String, List<Op>>()
        subs["a"] = subA
        subs["b"] = subB
        val flow = ir(listOf(imgTarget), emptyList(), subs, 30000L)
        val report = validate(flow, allCaps)
        assertTrue(
            report.errors.any { it.code == "SUB_CYCLE" },
            "has SUB_CYCLE"
        )
    }

    // Test: CAP_MISSING when SCREEN_CAPTURE absent with img target ------------
    check("cap missing screen capture") {
        val body = listOf(
            Op.Action(
                call = "tap",
                args = listOf("login_btn"),
                timeoutMs = null,
                onTimeout = emptyList(),
                onFail = emptyList(),
                span = s9
            )
        )
        val flow = ir(listOf(imgTarget), body, emptyMap(), 30000L)
        val granted = setOf("OCR", "INPUT_INJECT") // SCREEN_CAPTURE missing
        val report = validate(flow, granted)
        assertTrue(
            report.warnings.any {
                it.code == "CAP_MISSING" &&
                    it.message.contains("SCREEN_CAPTURE") &&
                    it.span == s9
            },
            "has CAP_MISSING SCREEN_CAPTURE"
        )
        // Input inject present -> should not warn for it.
        assertTrue(
            !report.warnings.any { it.message.contains("INPUT_INJECT") },
            "no INPUT_INJECT warning"
        )
    }

    // Test: UNBOUNDED_LOOP warning --------------------------------------------
    check("unbounded loop warning") {
        val body = listOf(
            Op.Loop(
                label = "free",
                everyMs = 100L,
                maxIter = null,
                body = emptyList(),
                span = s10
            )
        )
        val flow = ir(listOf(imgTarget), body, emptyMap(), 30000L)
        val report = validate(flow, allCaps)
        assertTrue(
            report.warnings.any { it.code == "UNBOUNDED_LOOP" && it.span == s10 },
            "has UNBOUNDED_LOOP at s10"
        )
    }

    // Test: spans preserved ---------------------------------------------------
    check("spans preserved") {
        val body = listOf(
            Op.Call(ref = "nope", span = s11),
            Op.Break(span = s11)
        )
        val flow = ir(listOf(imgTarget), body, emptyMap(), 30000L)
        val report = validate(flow, allCaps)
        assertTrue(
            report.errors.any { it.code == "UNBOUND_SUB" && it.span == s11 },
            "UNBOUND_SUB span preserved"
        )
        assertTrue(
            report.errors.any { it.code == "BREAK_OUTSIDE_LOOP" && it.span == s11 },
            "BREAK_OUTSIDE_LOOP span preserved"
        )
    }

    println("ALL M7A TESTS PASSED")
}