package com.macroengine.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.macroengine.dsl.RunResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Settings.canDrawOverlays(this)) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48,48,48,48) }

        root.addView(Button(this).apply {
            text = "Start Floating Overlay"
            setOnClickListener {
                startService(Intent(this@MainActivity, FloatingOverlayService::class.java))
                Toast.makeText(this@MainActivity, "Overlay started — drag the button, tap it for the menu.", Toast.LENGTH_LONG).show()
            }
        })

        root.addView(Button(this).apply {
            text = "Load test script"
            setOnClickListener {
                val c = MacroApp.instance.coordinator
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val ir = c.loadScript("on start:\n  log(\"hello from MacroEngine\")\n")
                        MacroApp.instance.currentScript = null // overlay uses recorded scripts; this just warms the parser
                        runOnUiThread { Toast.makeText(this@MainActivity, "Parser OK: ${ir.body.size} top ops", Toast.LENGTH_LONG).show() }
                    } catch (t: Throwable) { runOnUiThread { Toast.makeText(this@MainActivity, "parse err: ${t.message}", Toast.LENGTH_LONG).show() } }
                }
            }
        })

        setContentView(root)
    }
}
