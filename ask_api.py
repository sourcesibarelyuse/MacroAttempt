import sys, json, time, urllib.request, urllib.error, os

API_KEY = os.environ.get("CVC_API_KEY", "")
BASE_URL = "https://shprotoness-ai.jq9gfk.workers.dev/v1"
MODEL = "deepseek-v4.1-flash"

prompt = open(sys.argv[1], errors="ignore").read()
out_file = sys.argv[2] if len(sys.argv) > 2 else None
max_tokens = int(sys.argv[3]) if len(sys.argv) > 3 else 4000
body = json.dumps({"model": MODEL, "messages": [{"role": "user", "content": prompt}], "max_tokens": max_tokens}).encode()

txt = None
for attempt in range(1, 4):
    req = urllib.request.Request(
        BASE_URL.rstrip("/") + "/chat/completions", data=body,
        headers={"Content-Type": "application/json", "Authorization": "Bearer " + API_KEY, "User-Agent": "Mozilla/5.0"})
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            raw = r.read().decode("utf-8", errors="replace")
        try:
            txt = json.loads(raw)["choices"][0]["message"]["content"]
            break
        except (ValueError, KeyError, IndexError):
            print("Attempt %d: non-JSON response, first 300 chars: %s" % (attempt, raw[:300]))
    except urllib.error.HTTPError as e:
        print("Attempt %d: HTTP %s: %s" % (attempt, e.code, e.read().decode("utf-8", errors="replace")[:300]))
    except Exception as e:
        print("Attempt %d: %s" % (attempt, e))
    time.sleep(5 * attempt)

if txt is None:
    print("FAILED after 3 attempts; no output written")
    sys.exit(1)

txt = "\n".join(l for l in txt.split("\n") if not l.strip().startswith("```"))
if out_file:
    open(out_file, "w", encoding="utf-8").write(txt)
    print("Saved " + out_file + " (" + str(len(txt)) + " chars)")
else:
    print(txt)
