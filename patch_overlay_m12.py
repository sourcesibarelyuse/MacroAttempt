p = "macro-engine/app/src/main/kotlin/com/macroengine/app/FloatingOverlayService.kt"
s = open(p).read()

# import the new types
s = s.replace("import kotlin.math.hypot",
              "import kotlin.math.hypot\nimport com.macroengine.dsl.FlowIr")

# toggleRecording -> mount/unmount CaptureView + collect points
old = "    private fun toggleRecording() {\n        isRecording = !isRecording"
new = ("    private var captureView: CaptureView? = null\n\n"
       "    private fun toggleRecording() {\n        isRecording = !isRecording\n"
       "        if (isRecording) {\n"
       "            RecorderState.recording = true\n"
       "            RecorderState.points.clear()\n"
       "            RecorderState.screenW = resources.displayMetrics.widthPixels\n"
       "            RecorderState.screenH = resources.displayMetrics.heightPixels\n"
       "            val cv = CaptureView(this, { x, y -> addPointFromTouch(x.toFloat(), y.toFloat()) }, { _, _ -> })\n"
       "            val lp = WindowManager.LayoutParams(\n"
       "                WindowManager.LayoutParams.MATCH_PARENT,\n"
       "                WindowManager.LayoutParams.MATCH_PARENT,\n"
       "                overlayType(),\n"
       "                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,\n"
       "                PixelFormat.TRANSLUCENT)\n"
       "            try { windowManager.addView(cv, lp); captureView = cv } catch (_: Exception) {}\n"
       "        } else {\n"
       "            RecorderState.recording = false\n"
       "            captureView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }\n"
       "            captureView = null\n"
       "            buildScriptFromRecording()\n"
       "        }")
assert old in s, "toggleRecording not found"
s = s.replace(old, new)

# add buildScriptFromRecording + expanded menu actions (Loop/Speed/Save)
anchor = "    private fun startPlayback() {"
extra = ("    private fun buildScriptFromRecording() {\n"
         "        val pts = RecorderState.points.toList()\n"
         "        if (pts.isEmpty()) { toast(\"No points recorded\"); return }\n"
         "        val steps = pts.map { (x, y) ->\n"
         "            val n = norm(x, y, RecorderState.screenW, RecorderState.screenH)\n"
         "            Step(StepType.TAP, n.first, n.second, ms = 0)\n"
         "        }\n"
         "        MacroApp.instance.currentScript = Script(\"recorded\", steps, loopCount = 1, speedMult = 1.0)\n"
         "        toast(\"Recorded ${pts.size} points\")\n"
         "    }\n\n"
         "    private fun playCurrent() {\n"
         "        val s = MacroApp.instance.currentScript ?: run { toast(\"No script\"); return }\n"
         "        val ir: FlowIr = ScriptCompiler.compile(s)\n"
         "        hideMenu()\n"
         "        Thread { try { MacroApp.instance.coordinator.execute(ir) } catch (e: Exception) { toast(\"err ${e.message}\") } }.start()\n"
         "    }\n\n")
assert anchor in s
s = s.replace(anchor, extra + anchor)

# make startPlayback use playCurrent (keep old body harmless) and add loop/speed/save buttons in addPopupMenu
s = s.replace('makeMenuButton("Play", Color.parseColor("#FF2E7D32")) {\n            startPlayback()\n        })',
              'makeMenuButton("Play", Color.parseColor("#FF2E7D32")) {\n            playCurrent()\n        })')
s = s.replace('menuView.addView(makeMenuButton(\n            if (isRecording) "Stop Rec" else "Add Point",\n            Color.parseColor("#FF1565C0")\n        ) {\n            toggleRecording()\n        })',
              'menuView.addView(makeMenuButton(\n            if (isRecording) "Stop Rec" else "Add Point",\n            Color.parseColor("#FF1565C0")\n        ) {\n            toggleRecording()\n        })\n        menuView.addView(makeMenuButton("Loop", Color.parseColor("#FF6A1B9A")) { MacroApp.instance.currentScript?.let { MacroApp.instance.currentScript = it.copy(loopCount = if (it.loopCount > 1) 1 else 99) }; toast("loop toggled") })\n        menuView.addView(makeMenuButton("Save", Color.parseColor("#FF00838F")) { MacroApp.instance.currentScript?.let { toast(ScriptCodec.toJson(it).take(40) + "...") } ?: toast("no script") })')

open(p, "w").write(s)
print("overlay wired: recorder mount, buildScriptFromRecording, playCurrent, Loop/Save buttons")
