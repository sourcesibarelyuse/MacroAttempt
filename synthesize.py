import os, glob, json, urllib.request

API_KEY = os.environ.get("CVC_API_KEY", "")
BASE_URL = "https://shprotoness-ai.jq9gfk.workers.dev/v1"
MODEL = "deepseek-v4.1-flash"

# 1. Read ALL analysis files (both deep_*.txt and notes_*.txt)
files = glob.glob("deep_*.txt") + glob.glob("notes_*.txt")
combined_notes = ""
for f in files:
    try:
        with open(f, "r", errors="ignore") as file:
            combined_notes += f"\n\n--- SOURCE REPORT: {f} ---\n\n" + file.read()
    except Exception as e:
        print(f"Warning: Could not read {f}: {e}")

# 2. The Strict Synthesis Prompt
prompt = f"""You are a Senior Software Architect specializing in automation and macro tools. 
Below are detailed reverse-engineering reports of professional macro tools (PC and Android).

YOUR TASK:
Synthesize these reports into a SINGLE, COMPLETE, and self-contained Markdown document named 'Architecture.md'. 

CRITICAL RULES:
1. DO NOT truncate the output. Write out every section fully.
2. IGNORE all game-specific logic (Roblox, coordinates, webhooks). Focus ONLY on the generic engine architecture.
3. Use clean Markdown formatting with code blocks where necessary.

You MUST include these exact sections in this order:
1. **High-Level Architecture**: Design philosophy and layered diagram (Ports & Adapters).
2. **Core Modules**: 
   - Input Injection (GestureIR, Android dispatchGesture, MotionEvent fallback, Root fallback, PC SendInput).
   - Screen Capture & State Detection (MediaProjection, AccessibilityScreenshot, DXGI/GDI, pixel/template matching).
   - Service Lifecycle & Permissions (PermissionBroker, Foreground Service survival, Root vs Non-Root fallback).
3. **Data Flow**: Step-by-step macro execution lifecycle (Load -> Permission Check -> Initialization -> Execution Loop -> Teardown).
4. **API & OS Integration Summary**: A comparison table of Android vs PC implementations.
5. **Next Steps for Implementation**: Actionable first steps for coding the engine.

SOURCE REPORTS:
{combined_notes}
"""

# 3. Send to DeepSeek (Increased max_tokens to 8000 to prevent cutoff)
req = urllib.request.Request(
    BASE_URL.rstrip("/") + "/chat/completions",
    data=json.dumps({
        "model": MODEL,
        "messages": [{"role": "user", "content": prompt}],
        "max_tokens": 8000 
    }).encode(),
    headers={
        "Content-Type": "application/json",
        "Authorization": "Bearer " + API_KEY,
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }
)

# 4. Save the output directly to Architecture.md
try:
    print("Sending request to DeepSeek (this may take 1-2 minutes)...")
    with urllib.request.urlopen(req) as r:
        out = json.load(r)
    
    content = out["choices"][0]["message"]["content"]
    
    with open("Architecture.md", "w", encoding="utf-8") as f:
        f.write(content)
    
    # Quick validation: check if it ends properly
    if content.strip().endswith("```") or content.strip().endswith("Steps for Implementation"):
        print("✅ Successfully created a COMPLETE Architecture.md")
    else:
        print("⚠️ Warning: Output might have been truncated. Check the end of Architecture.md")
        
except urllib.error.HTTPError as e:
    print("❌ API Error " + str(e.code) + ": " + e.read().decode())
except Exception as e:
    print("❌ Error: " + str(e))
