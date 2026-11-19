Emit FOUR separate files, each preceded by a line exactly "// FILE: <relative/path>". Output ONLY file content (Kotlin/XML), no prose. Use // comments only. NO triple-quoted raw strings.
FILE 1 // FILE: macro-engine/app/src/main/kotlin/com/macroengine/app/ScriptModel.kt
package com.macroengine.app. Define a game-automation script model (FishScope-style):
- enum StepType { TAP, SWIPE, WAIT, COLOR_WAIT, LOOP }
- data class Step(type:StepType, x:Double=0.0, y:Double=0.0, x2:Double=0.0, y2:Double=0.0, ms:Long=0L, color:Int=0, tol:Int=0, loopTimes:Int=1, label:String="")  // x,y normalized 0..1
- data class Script(name:String, steps:List<Step>, loopCount:Int=1, speedMult:Double=1.0)
- object ScriptCodec { fun toJson(s:Script):String; fun fromJson(s:String):Script }  // hand-rolled minimal JSON, escape strings, no external lib
- fun validate(s:Script):List<String>  // returns errors: coords in [0,1], ms>=0, loopTimes>=1, speedMult>0
- math helpers: fun norm(px:Int,py:Int,w:Int,h:Int):Pair<Double,Double>; fun denorm(x:Double,y:Double,w:Int,h:Int):Pair<Int,Int>; fun jittered(ms:Long, seed:Long):Long  // +/-10% deterministic
FILE 2 // FILE: macro-engine/app/src/main/kotlin/com/macroengine/app/TouchRecorder.kt
package com.macroengine.app. A transparent full-screen capture view used during "Add Point" mode:
- class CaptureView(context:Context, onDown:(x:Int,y:Int)->Unit, onUp:(x:Int,y:Int)->Unit):View  // overrides onTouchEvent, returns true, reports rawX/rawY then calls invalidate()
- object RecorderState { var recording:Boolean=false; var points:MutableList<Pair<Int,Int>>=mutableListOf(); var screenW=0; var screenH=0 }  // holds live recording
- fun addPointFromTouch(rawX:Float,rawY:Float)  // pushes into RecorderState.points, clamps to screen
FILE 3 // FILE: macro-engine/app/src/main/kotlin/com/macroengine/app/ScriptCompiler.kt
package com.macroengine.app. Converts Script -> com.macroengine.dsl.FlowIr using EXACT M6 constructors: Op.Call(ref,span), Op.Loop(label,everyMs,maxIter,body,span), Op.If(cond:String,thenBody,elseBody,span), Op.Action(call:String,args:List<String>,timeoutMs:Long?,onTimeout,onFail,span), Op.Log(msg,span), Meta(name,timeoutMs:Long,requireCaps), FlowIr(irVersion,flowId,meta,targets,body,subs:Map<String,List<Op>>). Span(line,col).
- object ScriptCompiler { fun compile(s:Script):FlowIr }  // TAP->Action("tap",["x","y"],null,...); SWIPE->Action("swipe",[x,y,x2,y2],...); WAIT->Action("wait",[ms],...); COLOR_WAIT->If(cond="color:"+Integer.toHexString(color)+":"+tol, thenBody=[Action("proceed",...)], elseBody=[Action("wait",[50],...)] wrapped in Op.Loop(label=null,everyMs=50,maxIter=40,body=[...]); LOOP step->Op.Loop(label=step.loopTimes.toString(),everyMs=0,maxIter=step.loopTimes,body=compileSteps(sub)); speedMult scales every ms via jittered(). Wrap whole thing in Op.Loop(label="script",everyMs=0,maxIter=s.loopCount,body=steps).
- fun stepsToOps(steps:List<Step>, speed:Double):List<Op>
FILE 4 // FILE: macro-engine/app/src/main/kotlin/com/macroengine/app/HauaMenu.kt
package com.macroengine.app. The expanded Haua-style FAB menu (pure view-builder, no Service deps):
- class MenuBuilder(context:Context) { fun build(actions:Map<String,()->Unit>, state:MenuState):LinearLayout }  // vertical LinearLayout of rounded colored buttons, one per action key, label from key, color from a fixed palette map
- data class MenuState(loop:Boolean, speed:Double, recording:Boolean, hasScript:Boolean)
- val palette:Map<String,Int>  // Record, Play, Stop, Loop, Speed+, Speed-, Save, Load, Settings -> colors
