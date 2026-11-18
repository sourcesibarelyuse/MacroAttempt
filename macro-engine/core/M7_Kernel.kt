// M7b: execution kernel for the macro DSL IR — single pure Kotlin file.

package com.macroengine.dsl

// ---- Kernel IR ----------------------------------------------------------

sealed class KOp {
    data class CallSub(val ref: String) : KOp()
    object Return : KOp()
    data class LoopBegin(val label: String?, val everyMs: Long, val maxIter: Int?) : KOp()
    data class LoopEnd(val label: String?) : KOp()
    data class Act(
        val call: String,
        val args: List<String>,
        val timeoutMs: Long?,
        val onTimeoutPc: Int,
        val onFailPc: Int
    ) : KOp()

    data class Cond(val expr: String, val thenPc: Int, val elsePc: Int) : KOp()
    data class BreakTo(val pc: Int) : KOp()
    data class ContinueTo(val pc: Int) : KOp()
    data class LogOp(val msg: String) : KOp()
    object Halt : KOp()
}

class Program(
    val ops: List<KOp>,
    val subEntry: Map<String, Int>
)

// ---- Ports --------------------------------------------------------------

interface ActionPort {
    fun invoke(call: String, args: List<String>, timeoutMs: Long?, cb: (ok: Boolean, timedOut: Boolean) -> Unit)
}

interface StatePort {
    fun query(expr: String): Boolean
}

interface M7Clock {
    fun nowMs(): Long
}

interface LogPort {
    fun log(m: String)
}

// ---- Compiler -----------------------------------------------------------

class FlowCompiler {
    private val out = ArrayList<KOp>()
    private val subEntry = LinkedHashMap<String, Int>()
    private val loopStack = ArrayList<LoopCtx>()
    private val pendingHandlers = ArrayList<PendingHandler>()

    private class LoopCtx(
        val beginPc: Int,
        val pendingBreaks: MutableList<Int> = ArrayList(),
        val pendingContinues: MutableList<Int> = ArrayList()
    )

    private class PendingHandler(
        val actPc: Int,
        val onTimeoutOps: List<Op>,
        val onFailOps: List<Op>
    )

    fun compile(ir: FlowIr): Program {
        out.clear()
        subEntry.clear()
        loopStack.clear()
        pendingHandlers.clear()

        emitBody(ir.body)
        out.add(KOp.Halt)

        for ((name, subBody) in ir.subs) {
            subEntry[name] = out.size
            emitBody(subBody)
            out.add(KOp.Return)
        }

        // Append out-of-line action handlers after all body/subs.
        for (ph in pendingHandlers) {
            val act = out[ph.actPc] as KOp.Act
            var onTimeoutPc = ph.actPc + 1
            var onFailPc = ph.actPc + 1
            if (ph.onTimeoutOps.isNotEmpty()) {
                onTimeoutPc = out.size
                emitBody(ph.onTimeoutOps)
                out.add(KOp.ContinueTo(ph.actPc + 1))
            }
            if (ph.onFailOps.isNotEmpty()) {
                onFailPc = out.size
                emitBody(ph.onFailOps)
                out.add(KOp.ContinueTo(ph.actPc + 1))
            }
            out[ph.actPc] = act.copy(onTimeoutPc = onTimeoutPc, onFailPc = onFailPc)
        }

        return Program(out.toList(), subEntry.toMap())
    }

    private fun emitBody(ops: List<Op>) {
        for (op in ops) emitOp(op)
    }

    private fun emitOp(op: Op) {
        when (op) {
            is Op.Cond -> { /* statement-level cond: no-op */ }
            is Op.Call -> out.add(KOp.CallSub(op.ref))

            is Op.Log -> out.add(KOp.LogOp(op.msg))

            is Op.Action -> {
                val actPc = out.size
                out.add(KOp.Act(op.call, op.args, op.timeoutMs, -1, -1))
                pendingHandlers.add(PendingHandler(actPc, op.onTimeout, op.onFail))
            }

            is Op.If -> {
                val condPc = out.size
                out.add(KOp.Cond(op.cond, condPc + 1, -1))
                val thenStart = out.size
                emitBody(op.thenBody)
                val jumpPc = out.size
                out.add(KOp.ContinueTo(-1))
                val elseStart = out.size
                out.add(KOp.Cond(op.cond, thenStart, elseStart)) // placeholder unused
                out.removeAt(out.size - 1)
                // Patch cond else target.
                out[condPc] = (out[condPc] as KOp.Cond).copy(elsePc = elseStart)
                emitBody(op.elseBody)
                val afterElse = out.size
                out[jumpPc] = KOp.ContinueTo(afterElse)
            }

            is Op.Loop -> {
                val beginPc = out.size
                out.add(KOp.LoopBegin(op.label, op.everyMs, op.maxIter))
                loopStack.add(LoopCtx(beginPc))
                emitBody(op.body)
                val endPc = out.size
                out.add(KOp.LoopEnd(op.label))
                val ctx = loopStack.removeAt(loopStack.size - 1)
                for (brk in ctx.pendingBreaks) {
                    out[brk] = KOp.BreakTo(endPc + 1)
                }
                for (cont in ctx.pendingContinues) {
                    out[cont] = KOp.ContinueTo(beginPc)
                }
            }

            is Op.Break -> {
                val brkPc = out.size
                out.add(KOp.BreakTo(-1))
                if (loopStack.isNotEmpty()) loopStack.last().pendingBreaks.add(brkPc)
            }

            is Op.Continue -> {
                val contPc = out.size
                out.add(KOp.ContinueTo(-1))
                if (loopStack.isNotEmpty()) loopStack.last().pendingContinues.add(contPc)
            }

            is Op.Return -> out.add(KOp.Return)
        }
    }
}

// ---- Runner -------------------------------------------------------------

sealed class RunResult {
    object Completed : RunResult()
    data class Failed(val reason: String) : RunResult()
}

class RunLane(
    private val program: Program,
    private val actionPort: ActionPort,
    private val statePort: StatePort,
    private val clock: M7Clock,
    private val logPort: LogPort,
    private val advanceClock: (Long) -> Unit
) {
    private class ActiveLoop(val beginPc: Int, var iter: Int, var lastStart: Long)

    private val loops = ArrayList<ActiveLoop>()
    private val callStack = ArrayList<Int>()

    fun run(): RunResult {
        var pc = 0
        val ops = program.ops

        while (true) {
            if (pc < 0 || pc >= ops.size) {
                return RunResult.Failed("pc_out_of_range")
            }

            val op = ops[pc]
            when (op) {
                is KOp.Halt -> return RunResult.Completed

                is KOp.LogOp -> {
                    logPort.log(op.msg)
                    pc++
                }

                is KOp.CallSub -> {
                    val target = program.subEntry[op.ref]
                        ?: return RunResult.Failed("sub_missing:" + op.ref)
                    callStack.add(pc + 1)
                    pc = target
                }

                is KOp.Return -> {
                    if (callStack.isEmpty()) return RunResult.Completed
                    pc = callStack.removeAt(callStack.size - 1)
                }

                is KOp.Cond -> {
                    val result = statePort.query(op.expr)
                    pc = if (result) op.thenPc else op.elsePc
                }

                is KOp.BreakTo -> pc = op.pc

                is KOp.ContinueTo -> pc = op.pc

                is KOp.LoopBegin -> {
                    val top = loops.lastOrNull()
                    val isReentry = top != null && top.beginPc == pc
                    if (isReentry) {
                        top!!.iter += 1
                    } else {
                        loops.add(ActiveLoop(pc, 1, clock.nowMs()))
                    }
                    val frame = loops.last()
                    if (op.maxIter != null && frame.iter > op.maxIter) {
                        val endPc = findMatchingLoopEnd(pc)
                        loops.removeAt(loops.size - 1)
                        if (endPc < 0) return RunResult.Failed("loop_end_missing")
                        pc = endPc + 1
                    } else {
                        if (isReentry) {
                            val d = frame.lastStart - clock.nowMs()
                            if (d > 0) advanceClock(d)
                        }
                        pc = pc + 1
                    }
                }

                is KOp.LoopEnd -> {
                    val frame = loops.lastOrNull() ?: return RunResult.Failed("loop_end_orphan")
                    val every = (program.ops[frame.beginPc] as KOp.LoopBegin).everyMs
                    frame.lastStart = maxOf(clock.nowMs(), frame.lastStart + every)
                    pc = frame.beginPc
                }

                is KOp.Act -> {
                    val actPc = pc
                    val done = arrayOf(false)
                    actionPort.invoke(op.call, op.args, op.timeoutMs) { ok, timedOut ->
                        if (done[0]) return@invoke
                        done[0] = true
                        if (timedOut) {
                            nextPc = op.onTimeoutPc
                        } else if (!ok) {
                            nextPc = op.onFailPc
                        } else {
                            nextPc = actPc + 1
                        }
                    }
                    pc = nextPc
                }
            }
        }
    }

    private var nextPc: Int = 0

    private fun findMatchingLoopEnd(beginPc: Int): Int {
        val ops = program.ops
        var depth = 0
        var i = beginPc
        while (i < ops.size) {
            when (ops[i]) {
                is KOp.LoopBegin -> depth++
                is KOp.LoopEnd -> {
                    depth--
                    if (depth == 0) return i
                }
                else -> {}
            }
            i++
        }
        return -1
    }
}

// ---- Main / tests -------------------------------------------------------

private class ManualClock(var t: Long = 0L) : M7Clock {
    override fun nowMs(): Long = t
}

private class CollectingLogPort : LogPort {
    val lines = ArrayList<String>()
    override fun log(m: String) {
        lines.add(m)
    }
}

private class FakeActionPort(
    private val onCall: (String, (ok: Boolean, timedOut: Boolean) -> Unit) -> Unit
) : ActionPort {
    override fun invoke(
        call: String,
        args: List<String>,
        timeoutMs: Long?,
        cb: (ok: Boolean, timedOut: Boolean) -> Unit
    ) {
        onCall(call, cb)
    }
}

private class FakeStatePort(private val map: Map<String, Boolean>) : StatePort {
    override fun query(expr: String): Boolean = map[expr] ?: false
}

private fun actSim(log: CollectingLogPort, logMsgs: Boolean = false): FakeActionPort =
    FakeActionPort { call, cb ->
        if (logMsgs) log.log(call)
        cb(true, false)
    }

private fun runTest(
    ir: FlowIr,
    actionPort: ActionPort,
    statePort: StatePort,
    clock: ManualClock,
    log: CollectingLogPort,
    advance: (Long) -> Unit
): RunResult {
    val program = FlowCompiler().compile(ir)
    val lane = RunLane(program, actionPort, statePort, clock, log, advance)
    return lane.run()
}

private fun meta(name: String = "flow"): Meta = Meta(name, 0L, emptyList())

private fun flow(body: List<Op>, subs: Map<String, List<Op>> = emptyMap()): FlowIr =
    FlowIr(1, "flow-1", meta(), emptyList(), body, subs)

private fun span(): Span = Span(1, 1)

fun main() {
    // 1. linear log order
    run {
        val log = CollectingLogPort()
        val clock = ManualClock()
        val body = listOf(
            Op.Log("a", span()),
            Op.Log("b", span()),
            Op.Log("c", span())
        )
        val r = runTest(flow(body), actSim(log), FakeStatePort(emptyMap()), clock, log) {}
        check(r == RunResult.Completed)
        check(log.lines == listOf("a", "b", "c"))
    }

    // 2. loop max=3 runs 3x
    run {
        val log = CollectingLogPort()
        val clock = ManualClock()
        val body = listOf(
            Op.Loop("L", 0L, 3, listOf(Op.Log("x", span())), span())
        )
        val r = runTest(flow(body), actSim(log), FakeStatePort(emptyMap()), clock, log) {}
        check(r == RunResult.Completed)
        check(log.lines == listOf("x", "x", "x"))
    }

    // 3. every=100 max=3 => clock exactly 200
    run {
        val log = CollectingLogPort()
        val clock = ManualClock()
        val body = listOf(
            Op.Loop("L", 100L, 3, listOf(Op.Log("t", span())), span())
        )
        val r = runTest(flow(body), actSim(log), FakeStatePort(emptyMap()), clock, log) { d ->
            clock.t += d
        }
        check(r == RunResult.Completed)
        check(clock.nowMs() == 200L) { "expected 200 got " + clock.nowMs() }
        check(log.lines.size == 3)
    }

    // 4. break exits past loop and post-log runs
    run {
        val log = CollectingLogPort()
        val clock = ManualClock()
        val body = listOf(
            Op.Loop("L", 0L, 10, listOf(
                Op.Log("i", span()),
                Op.Break(span())
            ), span()),
            Op.Log("after", span())
        )
        val r = runTest(flow(body), actSim(log), FakeStatePort(emptyMap()), clock, log) {}
        check(r == RunResult.Completed)
        check(log.lines == listOf("i", "after"))
    }

    // 5. continue re-enters (3x "i" then "done")
    run {
        val log = CollectingLogPort()
        val clock = ManualClock()
        val body = listOf(
            Op.Loop("L", 0L, 3, listOf(
                Op.Log("i", span()),
                Op.Continue(span()),
                Op.Log("never", span())
            ), span()),
            Op.Log("done", span())
        )
        val r = runTest(flow(body), actSim(log), FakeStatePort(emptyMap()), clock, log) {}
        check(r == RunResult.Completed)
        check(log.lines == listOf("i", "i", "i", "done"))
    }

    // 6. sub call/return
    run {
        val log = CollectingLogPort()
        val clock = ManualClock()
        val subBody = listOf(Op.Log("sub", span()))
        val body = listOf(
            Op.Log("pre", span()),
            Op.Call("mysub", span()),
            Op.Log("post", span())
        )
        val r = runTest(
            flow(body, mapOf("mysub" to subBody)),
            actSim(log), FakeStatePort(emptyMap()), clock, log
        ) {}
        check(r == RunResult.Completed)
        check(log.lines == listOf("pre", "sub", "post"))
    }

    // 7. on_timeout handler runs then CONTINUES
    run {
        val log = CollectingLogPort()
        val clock = ManualClock()
        val action = FakeActionPort { _, cb -> cb(false, true) }
        val body = listOf(
            Op.Action("act", emptyList(), 100L,
                listOf(Op.Log("timeout", span())),
                emptyList(),
                span()),
            Op.Log("after", span())
        )
        val r = runTest(flow(body), action, FakeStatePort(emptyMap()), clock, log) {}
        check(r == RunResult.Completed)
        check(log.lines == listOf("timeout", "after")) { "got " + log.lines }
    }

    // 8. on_fail handler runs then CONTINUES
    run {
        val log = CollectingLogPort()
        val clock = ManualClock()
        val action = FakeActionPort { _, cb -> cb(false, false) }
        val body = listOf(
            Op.Action("act", listOf("k"), 100L,
                emptyList(),
                listOf(Op.Log("fail", span())),
                span()),
            Op.Log("after", span())
        )
        val r = runTest(flow(body), action, FakeStatePort(emptyMap()), clock, log) {}
        check(r == RunResult.Completed)
        check(log.lines == listOf("fail", "after")) { "got " + log.lines }
    }

    // 9. success falls through
    run {
        val log = CollectingLogPort()
        val clock = ManualClock()
        val action = FakeActionPort { _, cb -> cb(true, false) }
        val body = listOf(
            Op.Action("act", emptyList(), 100L,
                listOf(Op.Log("timeout", span())),
                listOf(Op.Log("fail", span())),
                span()),
            Op.Log("after", span())
        )
        val r = runTest(flow(body), action, FakeStatePort(emptyMap()), clock, log) {}
        check(r == RunResult.Completed)
        check(log.lines == listOf("after"))
    }

    // 10. cond then / else
    run {
        val log = CollectingLogPort()
        val clock = ManualClock()
        val body = listOf(
            Op.If("flag", listOf(Op.Log("then", span())), listOf(Op.Log("else", span())), span())
        )
        runTest(flow(body), actSim(log), FakeStatePort(mapOf("flag" to true)), clock, log) {}
        check(log.lines == listOf("then"))

        val log2 = CollectingLogPort()
        runTest(
            flow(body), actSim(log2),
            FakeStatePort(mapOf("flag" to false)), ManualClock(), log2
        ) {}
        check(log2.lines == listOf("else"))
    }

    // 11. top-level Return skips tail
    run {
        val log = CollectingLogPort()
        val clock = ManualClock()
        val body = listOf(
            Op.Log("a", span()),
            Op.Return(span()),
            Op.Log("b", span())
        )
        val r = runTest(flow(body), actSim(log), FakeStatePort(emptyMap()), clock, log) {}
        check(r == RunResult.Completed)
        check(log.lines == listOf("a"))
    }

    // 12. nested inner pacing 200
    run {
        val log = CollectingLogPort()
        val clock = ManualClock()
        val inner = Op.Loop("inner", 100L, 2, listOf(Op.Log("in", span())), span())
        val outer = Op.Loop("outer", 0L, 2, listOf(inner), span())
        val r = runTest(flow(listOf(outer)), actSim(log), FakeStatePort(emptyMap()), clock, log) { d ->
            clock.t += d
        }
        check(r == RunResult.Completed)
        check(log.lines.size == 4)
        check(clock.nowMs() == 200L) { "expected 200 got " + clock.nowMs() }
    }

    // 13. empty handler falls through
    run {
        val log = CollectingLogPort()
        val clock = ManualClock()
        val action = FakeActionPort { _, cb -> cb(false, true) }
        val body = listOf(
            Op.Action("act", emptyList(), 100L, emptyList(), emptyList(), span()),
            Op.Log("after", span())
        )
        val r = runTest(flow(body), action, FakeStatePort(emptyMap()), clock, log) {}
        check(r == RunResult.Completed)
        check(log.lines == listOf("after"))
    }

    println("ALL M7B TESTS PASSED")
}
