p = "macro-engine/core/M7_Kernel.kt"
s = open(p).read()

# 1) move local patch helpers above flatten (Kotlin local funs are not hoisted)
start = s.index("        fun patchBreakContinue(beginPc: Int, endPc: Int) {")
end = s.index("        // Flatten main body.")
block = s[start:end]
s = s[:start] + s[end:]
anchor = s.index("        // Recursively flatten a body into `out`.")
s = s[:anchor] + block + s[anchor:]

# 2) patch then-end jump at emit time (correct target = after else body)
old = "                        patchCondTargets(pc, thenStart, elseStart)"
assert old in s
s = s.replace(old, old + "\n                        out[jumpIdx] = KOp.ContinueTo(afterElse)")

# 3) pacing: advance at LoopBegin re-entry, not LoopEnd; don't clobber lastStart
old = """                    if (isReentry) {
                        top!!.iter += 1
                    } else {
                        loops.add(ActiveLoop(pc, 1, clock.nowMs()))
                    }"""
new = """                    if (isReentry) {
                        top!!.iter += 1
                        val nowR = clock.nowMs()
                        val deltaR = top.lastStart - nowR
                        if (deltaR > 0) advanceClock(deltaR)
                    } else {
                        loops.add(ActiveLoop(pc, 1, clock.nowMs()))
                    }"""
assert old in s
s = s.replace(old, new)

old = """                    } else {
                        // record lastStart for pacing on first entry
                        frame.lastStart = clock.nowMs()
                        pc = pc + 1
                    }"""
new = """                    } else {
                        pc = pc + 1
                    }"""
assert old in s
s = s.replace(old, new)

old = """                    val nextAt = maxOf(now, frame.lastStart + every)
                    val delta = nextAt - now
                    if (delta > 0) advanceClock(delta)
                    frame.lastStart = nextAt"""
new = """                    frame.lastStart = maxOf(now, frame.lastStart + every)"""
assert old in s
s = s.replace(old, new)

open(p, "w").write(s)
print("patched M7b: local-fun order + then-end jump + pacing")
