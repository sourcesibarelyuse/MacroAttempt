p = "macro-engine/app/src/main/kotlin/com/macroengine/app/EngineCoordinator.kt"
s = open(p).read()
# Add stop() method right before execute()
if "fun stop()" not in s:
    s = s.replace(
        "fun execute(ir: FlowIr): RunResult {",
        "fun stop() {\n        // TODO: wire cancellation to RunLane in M12\n    }\n\n    fun execute(ir: FlowIr): RunResult {"
    )
    open(p, "w").write(s)
    print("Patched EngineCoordinator.kt: added stop()")
else:
    print("stop() already exists")
