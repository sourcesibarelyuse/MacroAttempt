p = "macro-engine/app/src/main/kotlin/com/macroengine/app/MacroApp.kt"
s = open(p).read()
# Add FlowIr import and currentScript property
s = s.replace(
    "import android.app.Application\n\nclass MacroApp : Application() {\n    companion object { lateinit var instance: MacroApp; private set }\n    lateinit var coordinator: EngineCoordinator; private set",
    "import android.app.Application\nimport com.macroengine.dsl.FlowIr\n\nclass MacroApp : Application() {\n    companion object { lateinit var instance: MacroApp; private set }\n    lateinit var coordinator: EngineCoordinator; private set\n    var currentScript: FlowIr? = null"
)
open(p, "w").write(s)
print("Patched MacroApp.kt: added currentScript")
