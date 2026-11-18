Write M7b: execution kernel for the macro DSL IR, ONE pure Kotlin file, package com.macroengine.dsl, zero Android imports. Output ONLY Kotlin code; // comments only; NO raw strings.
CRITICAL: Span, Op (and nested Call/Loop/If/Action/Cond/Break/Continue/Return/Log), TargetDecl, Meta, FlowIr ALREADY EXIST in M6_Dsl.kt, SAME package. DO NOT declare, redeclare, or "mirror" them — any such declaration causes redeclaration errors. Import nothing; just use them.
EXACT M6 shapes you must code against: Op.Call(ref,span); Op.Loop(label,everyMs,maxIter,body,span); Op.If(cond:String,thenBody,elseBody,span); Op.Action(call:String, args:List<String>, timeoutMs:Long?, onTimeout, onFail, span); Op.Log(msg,span); Meta(name,timeoutMs:Long,requireCaps); FlowIr(irVersion,flowId,meta,targets,body,subs:Map<String,List<Op>>).
Define: sealed class KOp { CallSub(ref); Return; LoopBegin(label,everyMs,maxIter); LoopEnd(label); Act(call:String, args:List<String>, timeoutMs:Long?, onTimeoutPc, onFailPc); Cond(expr,thenPc,elsePc); BreakTo(pc); ContinueTo(pc); LogOp(msg); Halt }. class Program(ops, subEntry).
Ports: ActionPort.invoke(call:String, args:List<String>, timeoutMs:Long?, cb:(ok,timedOut)->Unit); StatePort.query(expr):Boolean; M7Clock.nowMs():Long; LogPort.log(m).
FlowCompiler.compile(ir): emit top-level body then Halt, then each sub then Return.
- If: emit Cond(expr, thenPc=pc+1, elsePc=PH); then body; ContinueTo(PH); else body; afterElse. Patch Cond.elsePc=elseStart and the jump to afterElse at emit time.
- Loop: emit LoopBegin; push LoopCtx(beginPc, pendingBreaks, pendingContinues); emit body; emit LoopEnd at endPc; THEN patch every pendingBreak index to BreakTo(endPc+1) and pendingContinue to ContinueTo(beginPc). Break/Continue emit BreakTo(-1)/ContinueTo(-1) and register into loopStack.last() — NEVER scan forward for LoopEnd at break-emit time (it does not exist yet).
- Action: emit Act(call,args,timeoutMs,PH,PH); record (actPc, onTimeoutOps, onFailOps). After ALL body+subs emitted, append each non-empty handler out-of-line: pc=out.size; emit handler; emit ContinueTo(actPc+1); patch Act.onTimeoutPc/onFailPc. Empty handler => actPc+1. Handlers NEVER end with Halt.
RunLane(program, actionPort, statePort, clock, logPort, advanceClock:(Long)->Unit).run():RunResult. private class ActiveLoop(beginPc, var iter, var lastStart).
PACING — implement LoopBegin and LoopEnd EXACTLY:
            is KOp.LoopBegin -> {
                val top = loops.lastOrNull()
                val isReentry = top != null && top.beginPc == pc
                if (isReentry) top!!.iter += 1 else loops.add(ActiveLoop(pc, 1, clock.nowMs()))
                val frame = loops.last()
                if (op.maxIter != null && frame.iter > op.maxIter) {
                    val endPc = findMatchingLoopEnd(pc)
                    loops.removeAt(loops.size - 1)
                    if (endPc < 0) return RunResult.Failed("loop_end_missing")
                    pc = endPc + 1
                } else {
                    if (isReentry) { val d = frame.lastStart - clock.nowMs(); if (d > 0) advanceClock(d) }
                    pc = pc + 1
                }
            }
            is KOp.LoopEnd -> {
                val frame = loops.lastOrNull() ?: return RunResult.Failed("loop_end_orphan")
                val every = (program.ops[frame.beginPc] as KOp.LoopBegin).everyMs
                frame.lastStart = maxOf(clock.nowMs(), frame.lastStart + every)
                pc = frame.beginPc
            }
Act: capture actPc; on cb: timedOut->onTimeoutPc, !ok->onFailPc, else actPc+1. BreakTo/ContinueTo jump; CallSub push pc+1; Return pop (top-level Return => Completed); Halt => Completed exactly once.
sealed RunResult { Completed; Failed(reason) }.
main(): ManualClock/CollectingLogPort/FakeActionPort/FakeStatePort; every RunLane gets a LogPort 5th. Tests (args use emptyList() or listOf("k")): linear log order; loop max=3 runs 3x; every=100 max=3 => clock exactly 200; break exits past loop and post-log runs; continue re-enters (3x "i" then "done"); sub call/return; on_timeout handler runs then CONTINUES to next stmt (expect ["timeout","after"]); on_fail likewise ["fail","after"]; success falls through; cond then/else; top-level Return skips tail; nested inner pacing 200; empty handler falls through. Print "ALL M7B TESTS PASSED".
