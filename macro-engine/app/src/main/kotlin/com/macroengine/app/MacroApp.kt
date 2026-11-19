package com.macroengine.app

import android.app.Application
import com.macroengine.dsl.FlowIr

class MacroApp : Application() {
    companion object { lateinit var instance: MacroApp; private set }
    lateinit var coordinator: EngineCoordinator; private set
    var currentScript: FlowIr? = null
    override fun onCreate() {
        super.onCreate()
        instance = this
        coordinator = EngineCoordinator(this)
    }
}
