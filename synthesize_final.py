import os, glob, json, urllib.request

API_KEY = os.environ.get("CVC_API_KEY", "")
BASE_URL = "https://shprotoness-ai.jq9gfk.workers.dev/v1"
MODEL = "deepseek-v4.1-flash"

# 1. Read all source files
files = glob.glob("deep_*.txt") + glob.glob("notes_*.txt")
combined_notes = ""
for f in files:
    try:
        with open(f, "r", errors="ignore") as file:
            combined_notes += f"\n\n--- SOURCE: {f} ---\n\n" + file.read()
    except Exception: pass

# 2. Define the sections to generate one by one
sections = [
    "1. High-Level Architecture (Design Philosophy, Layered Diagram, Ports & Adapters)",
    "2. Core Modules: Input Injection (GestureIR, Android Native/Legacy/Root, PC)",
    "3. Core Modules: Screen Capture & State Detection (MediaProjection, Accessibility, PC)",
    "4. Core Modules: Service Lifecycle & Permissions (Broker, Resilience, Fallbacks)",
    "5. Data Flow: Macro Execution Lifecycle & API/OS Integration Summary",
    "6. Next Steps for Implementation"
]

# 3. Start with a clean file
with open("Architecture.md", "w") as f:
    f.write("# Macro Automation Engine — Reference Architecture\n\n")

# 4. Generate and append each section
for i, section in enumerate(sections):
    print(f"Generating section {i+1}/{len(sections)}: {section}...")
    
    prompt = f"""You are writing Section {i+1} of a comprehensive Architecture.md document for a macro engine.
    ONLY write the Markdown content for this specific section. Do not repeat the main title.
    Use code blocks where necessary. Ignore game-specific logic.
    
    SECTION TO WRITE: {section}
    
    SOURCE MATERIAL:
    {combined_notes[:15000]}"""

    req = urllib.request.Request(
        BASE_URL.rstrip("/") + "/chat/completions",
        data=json.dumps({
            "model": MODEL,
            "messages": [{"role": "user", "content": prompt}],
            "max_tokens": 4000 # 4000 is plenty for one section and prevents cutoffs
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
        with open("Architecture.md", "a") as f:
            f.write(f"\n## {section}\n\n{content}\n\n")
        print(f"✅ Section {i+1} saved.")
    except Exception as e:
        print(f"❌ Error on section {i+1}: {e}")

print("🎉 Architecture.md is fully complete!")