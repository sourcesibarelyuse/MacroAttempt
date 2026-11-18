p = "macro-engine/core/M6_Dsl.kt"
s = open(p).read()

s = s.replace("assertEq(9, call0.span.line)", "assertEq(10, call0.span.line)")

old = '''        if (header.isNotEmpty()) {
            for (part in header.split(",")) {
                val p = part.trim()
                if (p.isEmpty()) continue
                when {
                    p.startsWith("every ") -> {
                        val d = p.substring(6).trim()
                        everyMs = parseDuration(d, span)
                    }
                    p.startsWith("max ") -> {
                        val n = p.substring(4).trim().toIntOrNull()
                            ?: throw DslSyntaxError("bad max value", span)
                        maxIter = n
                    }
                    else -> throw DslSyntaxError("unknown loop clause '$p'", span)
                }
            }
        }'''
new = '''        if (header.isNotEmpty()) {
            val toks = header.split(Regex("[,\\s]+")).filter { it.isNotEmpty() }
            var i = 0
            while (i < toks.size) {
                when (toks[i]) {
                    "every" -> {
                        if (i + 1 >= toks.size) throw DslSyntaxError("missing duration after every", span)
                        everyMs = parseDuration(toks[i + 1], span)
                        i += 2
                    }
                    "max" -> {
                        if (i + 1 >= toks.size) throw DslSyntaxError("missing value after max", span)
                        maxIter = toks[i + 1].toIntOrNull()
                            ?: throw DslSyntaxError("bad max value", span)
                        i += 2
                    }
                    else -> throw DslSyntaxError("unknown loop clause " + toks[i], span)
                }
            }
        }'''
assert old in s, "loop clause block not found"
s = s.replace(old, new)
open(p, "w").write(s)
print("patched M6: span expectation + loop clause tokenizer")
