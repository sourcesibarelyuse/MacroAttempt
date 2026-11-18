p = "macro-engine/core/M8_Detection.kt"
s = open(p).read()
s = s.replace(".toBits()", ".hashCode()")
s = s.replace('!colorMatch(0x5A96C8, t)', '!colorMatch(0x5996C8, t)')
open(p, "w").write(s)
print("patched M8: toBits->hashCode, fixed r-under test")
