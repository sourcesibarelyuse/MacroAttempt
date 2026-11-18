package com.macroengine.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private val testDsl = "on start:\n  log(\"hello\")\n"
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Settings.canDrawOverlays(this)) {
            runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
        }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48,48,48,48) }
        root.addView(Button(this).apply { text = "Run test macro"; setOnClickListener { run() } })
        setContentView(root)
    }
    private fun run() {
        val c = MacroApp.instance.coordinator
        if (!c.isReady()) { Toast.makeText(this, "Enable MacroEngine in Accessibility Settings.", Toast.LENGTH_LONG).show(); return }
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val r = c.execute(c.loadScript(testDsl))
                runOnUiThread { Toast.makeText(this@MainActivity, "Result: $r", Toast.LENGTH_LONG).show() }
            } catch (t: Throwable) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Error: ${t.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }
}
