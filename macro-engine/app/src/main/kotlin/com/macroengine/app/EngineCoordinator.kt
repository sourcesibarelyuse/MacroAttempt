package com.macroengine.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.macroengine.adapters.InjectorFactory
import com.macroengine.dsl.ActionPort
import com.macroengine.dsl.FlowCompiler
import com.macroengine.dsl.LogPort
import com.macroengine.dsl.M7Clock
import com.macroengine.dsl.Parser
import com.macroengine.dsl.RunLane
import com.macroengine.dsl.RunResult
import com.macroengine.dsl.StatePort
import com.macroengine.dsl.FlowIr

class EngineCoordinator(@Suppress("unused") private val context: Context) {

    fun isReady(): Boolean = com.macroengine.adapters.ServiceRegistry.isServiceConnected()

    fun loadScript(dsl: String): FlowIr = Parser(dsl, "flow").parse()

    fun execute(ir: FlowIr): RunResult {
        val injector = InjectorFactory.create()
        val program = FlowCompiler.compile(ir)
        // ActionPort: command dispatcher maps call+args -> GesturePlan and injects.
        // For the smoke test (log-only) no Act op is emitted, so this stub is never hit.
        val actionPort = object : ActionPort {
            override fun invoke(call: String, args: List<String>, timeoutMs: Long?, cb: (ok: Boolean, timedOut: Boolean) -> Unit) {
                // TODO: build GesturePlan via GestureBuilders + resolve targets, then injector.inject(...)
                cb(true, false)
            }
        }
        val statePort = object : StatePort { override fun query(expr: String): Boolean = false }
        val clock = object : M7Clock { override fun nowMs(): Long = SystemClock.elapsedRealtime() }
        val logPort = object : LogPort { override fun log(m: String) { Log.i("MacroEngine", m) } }
        return RunLane(program, actionPort, statePort, clock, logPort) { ms ->
            if (ms > 0) { try { Thread.sleep(ms) } catch (_: InterruptedException) {} }
        }.run()
    }
}
