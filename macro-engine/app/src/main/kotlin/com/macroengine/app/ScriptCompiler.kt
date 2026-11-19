package com.macroengine.app

import com.macroengine.dsl.FlowIr
import com.macroengine.dsl.Meta
import com.macroengine.dsl.Op
import com.macroengine.dsl.Span

object ScriptCompiler {

    private fun span(top: Boolean, depth: Int): Span = Span(if (top) 0 else depth, 0)

    fun compile(s: Script): FlowIr {
        val body = stepsToOps(s.steps, s.speedMult)
        val wrapped = listOf(
            Op.Loop(
                label = "script",
                everyMs = 0L,
                maxIter = s.loopCount,
                body = body,
                span = span(true, 0)
            )
        )
        val meta = Meta(
            name = s.name,
            timeoutMs = 0L,
            requireCaps = emptyList()
        )
        return FlowIr(
            irVersion = 1,
            flowId = "script:" + s.name,
            meta = meta,
            targets = emptyList(),
            body = wrapped,
            subs = emptyMap()
        )
    }

    fun stepsToOps(steps: List<Step>, speed: Double): List<Op> {
        val mult = if (speed > 0.0) speed else 1.0
        val out = mutableListOf<Op>()
        var idx = 0
        for (step in steps) {
            val sp = span(false, idx)
            when (step.type) {
                StepType.TAP -> {
                    val args = listOf(step.x.toString(), step.y.toString())
                    out.add(Op.Action("tap", args, null, emptyList(), emptyList(), sp))
                }
                StepType.SWIPE -> {
                    val args = listOf(
                        step.x.toString(),
                        step.y.toString(),
                        step.x2.toString(),
                        step.y2.toString()
                    )
                    out.add(Op.Action("swipe", args, null, emptyList(), emptyList(), sp))
                }
                StepType.WAIT -> {
                    val scaled = jittered((step.ms.toDouble() / mult).toLong(), (idx + 1).toLong())
                    val args = listOf(scaled.toString())
                    out.add(Op.Action("wait", args, null, emptyList(), emptyList(), sp))
                }
                StepType.COLOR_WAIT -> {
                    val cond = "color:" + Integer.toHexString(step.color) + ":" + step.tol
                    val thenBody = listOf(Op.Action("proceed", emptyList(), null, emptyList(), emptyList(), sp))
                    val waitMs = jittered((50L.toDouble() / mult).toLong(), (idx + 1).toLong())
                    val innerWait = listOf(
                        Op.Action("wait", listOf(waitMs.toString()), null, emptyList(), emptyList(), sp),
                        Op.If(cond = cond, thenBody = thenBody, elseBody = emptyList(), span = sp)
                    )
                    val loop = Op.Loop(
                        label = null,
                        everyMs = waitMs,
                        maxIter = 40,
                        body = innerWait,
                        span = sp
                    )
                    out.add(loop)
                }
                StepType.LOOP -> {
                    val times = if (step.loopTimes < 1) 1 else step.loopTimes
                    val sub = stepsToOps(listOf(step.copy(type = StepType.WAIT, loopTimes = 1, ms = step.ms)), mult)
                    val loop = Op.Loop(
                        label = times.toString(),
                        everyMs = 0L,
                        maxIter = times,
                        body = sub,
                        span = sp
                    )
                    out.add(loop)
                }
            }
            idx++
        }
        return out
    }
}
