package com.macroengine.app

import android.app.Application

class MacroApp : Application() {
    companion object { lateinit var instance: MacroApp; private set }
    lateinit var coordinator: EngineCoordinator; private set
    var currentScript: Script? = null
    override fun onCreate() {
        super.onCreate()
        instance = this
        coordinator = EngineCoordinator(this)
    }
}
