p = "macro-engine/core/GeometryCore.kt"
s = open(p).read()
s = s.replace("require(radius > 0.0)", "require(radius >= 0.0)")
a = s.index("// Duration out of range.")
b = s.index("// Valid plan OK.")
s = s[:a] + '''// Duration out of range.
    checkThrows("duration > 60000 throws",
        { GesturePlan(listOf(Stroke(listOf(
            SampledPoint(0.0, 0.0, 0),
            SampledPoint(0.1, 0.1, 60001),
        )))) },
        { it is IllegalArgumentException })

    ''' + s[b:]
open(p, "w").write(s)
print("Patched 2 bugs")
