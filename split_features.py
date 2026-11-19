import re, os
txt = open("/tmp/features.txt").read()
# robust: handle //, <!--, # file headers
parts = re.split(r'(?m)^(?://|<!--|#)\s*FILE:\s*(\S+\.kt)\s*(?:-->)?\s*$', txt)
n = 0
for i in range(1, len(parts), 2):
    path, body = parts[i].strip(), parts[i+1].strip()
    body = "\n".join(l for l in body.split("\n") if not l.strip().startswith("```"))
    os.makedirs(os.path.dirname(path), exist_ok=True)
    open(path, "w").write(body + "\n")
    n += 1
    print("wrote", path)
print("files:", n)
