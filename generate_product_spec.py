import os, json, urllib.request

API_KEY = os.environ.get("CVC_API_KEY", "")
BASE_URL = "https://shprotoness-ai.jq9gfk.workers.dev/v1"
MODEL = "deepseek-v4.1-flash"

# Read the existing architecture for context so it doesn't repeat itself
context = ""
try:
    with open("Architecture.md", "r", errors="ignore") as f:
        context = f.read()[:3000] # Just grab the first 3000 chars for context
except Exception: pass

sections = [
    "1. The Scripting Engine & Task Manager: Design a simple but powerful scripting menu (DSL or lightweight JSON/YAML) that allows users to write scripts instead of just using a visual builder. It must handle complex, multi-step task sequences game-agnostically (like FishScope but universal). Include how the execution queue and loop drivers work.",
    "2. Low-Latency Detection Engine: How to implement Color, Text (OCR), and Image detection with near-zero delay so it doesn't lag the game. Focus on Android-specific optimizations (OpenCV via JNI, hardware acceleration, background threading, and memory pooling).",
    "3. UI/UX, Overlay, and Local-Only Architecture: Design the user interface, the floating overlay menu, and the configuration system. Explicitly state there is NO login, NO account system, and NO cloud sync. Everything is stored locally on the device."
]

with open("Product_Spec.md", "w") as f:
    f.write("# Product Specification: Universal Android Macro Engine\n\n")

for i, section in enumerate(sections):
    print(f"Generating Product Spec section {i+1}/{len(sections)}...")
    
    prompt = f"""You are a Lead Product Designer and Software Architect.
    We are building a universal Android macro engine for mobile games.
    
    CONTEXT FROM REFERENCE ARCHITECTURE:
    {context}
    
    YOUR TASK: Write Section {i+1} of the Product Specification.
    REQUIREMENT: {section}
    
    RULES:
    - Be highly technical and specific.
    - Do NOT repeat the low-level Android API stuff (like MediaProjection or AccessibilityService) from the context; focus on the APP LAYER.
    - Use clean Markdown.
    """

    req = urllib.request.Request(
        BASE_URL.rstrip("/") + "/chat/completions",
        data=json.dumps({
            "model": MODEL,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": 4000
        }).encode(),
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + API_KEY,
            "User-Agent": "Mozilla/5.0"
        }
    )

    try:
        with urllib.request.urlopen(req) as r:
            out = json.load(r)
        content = out["choices"][0]["message"]["content"]
        with open("Product_Spec.md", "a") as f:
            f.write(f"\n## {section.split(':')[0]}\n\n{content}\n\n")
        print(f"✅ Section {i+1} saved.")
    except Exception as e:
        print(f"❌ Error: {e}")

print("🎉 Product_Spec.md is complete!")