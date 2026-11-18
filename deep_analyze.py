import os, sys, json, urllib.request

API_KEY = os.environ.get("CVC_API_KEY", "")
BASE_URL = "https://shprotoness-ai.jq9gfk.workers.dev/v1"
MODEL = "deepseek-v4.1-flash"

target = sys.argv[1] if len(sys.argv) > 1 else "targets/pc/fishscope-src/main.py"

try:
    with open(target, "r", errors="ignore") as f:
        code = f.read()
except FileNotFoundError:
    print("Error: Could not find file " + target)
    sys.exit(1)

# Deep Analysis Prompt (No length limits)
prompt = f"""You are an expert reverse-engineer. Analyze the following source code from a macro/automation tool.
Provide a comprehensive technical report including:
1. **Core Architecture**: Main classes, inheritance, and design patterns.
2. **Key Methods**: Detailed explanation of the most important functions (especially those handling input injection, screen capture, or state detection).
3. **Data Flow**: How configuration is loaded and how the main loop executes.
4. **API Usage**: Specific Android/PC APIs used (e.g., AccessibilityService, dispatchGesture, ImageGrab, etc.).
5. **Logic Flow**: Step-by-step breakdown of the automation sequence.

CODE:
{code}"""

req = urllib.request.Request(
    BASE_URL.rstrip("/") + "/chat/completions",
    data=json.dumps({
        "model": MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": 4000 # Allows for a very long, detailed response
    }).encode(),
    headers={
        "Content-Type": "application/json",
        "Authorization": "Bearer " + API_KEY,
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }
)

try:
    with urllib.request.urlopen(req) as r:
        out = json.load(r)
    print(out["choices"][0]["message"]["content"])
except urllib.error.HTTPError as e:
    print("API Error " + str(e.code) + ": " + e.read().decode())