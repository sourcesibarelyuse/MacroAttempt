p = "macro-engine/core/M7_Kernel.kt"
s = open(p).read()
old = """                    if (isReentry) {
                        top!!.iter += 1
                        val nowR = clock.nowMs()
                        val deltaR = top.lastStart - nowR
                        if (deltaR > 0) advanceClock(deltaR)
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
                        pc = pc + 1
                    }"""
new = """                    if (isReentry) {
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
                            val nowR = clock.nowMs()
                            val deltaR = frame.lastStart - nowR
                            if (deltaR > 0) advanceClock(deltaR)
                        }
                        pc = pc + 1
                    }"""
assert old in s, "LoopBegin block not found"
s = s.replace(old, new)
open(p, "w").write(s)
print("patched M7b: pacing advance only on body-running visits")
