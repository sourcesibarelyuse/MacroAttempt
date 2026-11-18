import re
p = "macro-engine/core/M6_Dsl.kt"
s = open(p).read()
s = re.sub(r"\.trimIndent(?!\()", ".trimIndent()", s)
if "fun assertTrue(" not in s:
    helpers = '''
private fun assertTrue(cond: Boolean) {
    if (!cond) throw IllegalStateException("assertTrue failed")
}
private fun assertEq(expected: Any?, actual: Any?) {
    if (expected != actual) throw IllegalStateException("assertEq failed: expected=$expected actual=$actual")
}
'''
    i = s.index("fun main(")
    s = s[:i] + helpers + "\n" + s[i:]
open(p, "w").write(s)
print("patched M6: trimIndent() + assertion helpers")
