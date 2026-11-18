import os, json, urllib.request

API_KEY = os.environ.get("CVC_API_KEY", "")
BASE_URL = "https://shprotoness-ai.jq9gfk.workers.dev/v1"
MODEL = "deepseek-v4.1-flash"

USER_PROMPT = """
Generate the `build.gradle.kts` (Project and App level) and `settings.gradle.kts` for an Android app named 'MacroEngine'. 
We need dependencies for AndroidX, Material Design, Kotlin Coroutines, OpenCV (via Maven), and ML Kit Text Recognition. 
Also, list the exact folder structure we need to create for the `core`, `android-adapters`, and `app-ui` modules.
Provide the exact code for these gradle files and a tree view of the folders.
"""

context = ""
try:
    with open("Architecture.md", "r", errors="ignore") as f: context += f.read()[:2000]
except Exception: pass

prompt = f"""You are an expert Android/Kotlin developer. We are building a macro engine based on this architecture:
{context}

TASK:
{USER_PROMPT}

RULES:
- Write clean, production-ready code.
- Include necessary imports.
- If creating multiple files, clearly separate them with '--- FILE: path/to/File.kt ---'
"""

req = urllib.request.Request(
    BASE_URL.rstrip("/") + "/chat/completions",
    data=json.dumps({"model": MODEL, "messages": [{"role": "user", "content": prompt}], "max_tokens": 4000}).encode(),
    headers={"Content-Type": "application/json", "Authorization": "Bearer " + API_KEY, "User-Agent": "Mozilla/5.0"}
)

try:
    print("Asking AI for Project Setup...\n")
    with urllib.request.urlopen(req) as r:
        out = json.load(r)
    print(out["choices"][0]["message"]["content"])
except Exception as e:
    print("Error:", e)