p = "macro-engine/android-adapters/M4_LegacyTicker.kt"
lines = open(p).read().split("\n")
bad = ("Now the test harness", "Note: `main()`")
out = [l for l in lines if not l.strip().startswith(bad)]
open(p, "w").write("\n".join(out))
print("removed", len(lines) - len(out), "prose line(s)")
