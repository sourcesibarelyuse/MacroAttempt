import re
p = "macro-engine/core/M7_Kernel.kt"
s = open(p).read()

# 1) drop stray <final> / </final> lines
s = re.sub(r"(?m)^[ \t]*</?final>[ \t]*\n?", "", s)

# 2) add missing is Op.Cond branch to the emit when (first 'is Op.Call ->')
s = re.sub(r"(?m)^([ \t]*)(is Op\.Call ->)",
           r"\1is Op.Cond -> { /* statement-level cond: no-op */ }\n\1\2",
           s, count=1)

# 3) FlowIr irVersion must be Int
s = re.sub(r'FlowIr\("1",', "FlowIr(1,", s)

# 4) Span.Synthetic -> Span(1, 1)
s = s.replace("Span.Synthetic", "Span(1, 1)")

open(p, "w").write(s)
print("patched M7b: tags + exhaustive when + FlowIr + Span")
