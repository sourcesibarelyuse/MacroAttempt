p1 = "macro-engine/core/GeometryCore.kt"
s = open(p1).read()
if not s.lstrip().startswith("package"):
    open(p1,"w").write("package com.macroengine.core\n\n" + s)
    print("M1: added package com.macroengine.core")

p2 = "macro-engine/android-adapters/M2_Capability.kt"
c = open(p2).read()
if not c.lstrip().startswith("package"):
    open(p2,"w").write("package com.macroengine.adapters\n\n" + c)
    print("M2a: added package com.macroengine.adapters")

p3 = "macro-engine/android-adapters/M2_Android.kt"
t = open(p3).read()
t = t.replace("stroke.startTimeMs", "stroke.start.t")
t = t.replace("path.moveTo(first.x, first.y)", "path.moveTo(first.x.toFloat(), first.y.toFloat())")
t = t.replace("path.lineTo(p.x, p.y)", "path.lineTo(p.x.toFloat(), p.y.toFloat())")
t = t.replace("path.lineTo(first.x + 0.01f, first.y + 0.01f)", "path.lineTo(first.x.toFloat() + 0.01f, first.y.toFloat() + 0.01f)")
a = t.find("sealed class InjectionResult")
if a >= 0:
    b = t.find("}", t.find("object FAIL_NOT_AVAILABLE", a))
    t = t[:a] + t[b+1:]
    print("M2b: removed duplicate InjectionResult (uses M2a enum)")
open(p3,"w").write(t)
print("M2b: fixed startTimeMs + Float conversions")
