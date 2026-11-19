package com.macroengine.app

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

data class MenuState(
    val loop: Boolean,
    val speed: Double,
    val recording: Boolean,
    val hasScript: Boolean
)

val palette: Map<String, Int> = mapOf(
    "Record" to Color.parseColor("#E53935"),
    "Play" to Color.parseColor("#43A047"),
    "Stop" to Color.parseColor("#FB8C00"),
    "Loop" to Color.parseColor("#8E24AA"),
    "Speed+" to Color.parseColor("#039BE5"),
    "Speed-" to Color.parseColor("#00ACC1"),
    "Save" to Color.parseColor("#6D4C41"),
    "Load" to Color.parseColor("#3949AB"),
    "Settings" to Color.parseColor("#546E7A")
)

class MenuBuilder(private val context: Context) {

    private fun dp(v: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun makeButton(key: String, action: () -> Unit): TextView {
        val tv = TextView(context)
        tv.text = key
        tv.gravity = Gravity.CENTER
        tv.setTextColor(Color.WHITE)
        tv.textSize = 14f
        val baseColor = palette[key] ?: Color.parseColor("#616161")
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = dp(12).toFloat()
        bg.setColor(baseColor)
        tv.background = bg
        val padH = dp(16)
        val padV = dp(10)
        tv.setPadding(padH, padV, padH, padV)
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(6)
        lp.bottomMargin = dp(6)
        tv.layoutParams = lp
        tv.isClickable = true
        tv.setOnClickListener { action() }
        return tv
    }

    private fun decorate(key: String, tv: TextView, state: MenuState) {
        val base = palette[key] ?: Color.parseColor("#616161")
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.RECTANGLE
        bg.cornerRadius = dp(12).toFloat()
        when (key) {
            "Loop" -> {
                if (state.loop) bg.setColor(base) else bg.setColor(adjust(base, 0.5f))
            }
            "Record" -> {
                if (state.recording) bg.setColor(base) else bg.setColor(adjust(base, 0.6f))
            }
            "Play" -> {
                if (state.hasScript) bg.setColor(base) else bg.setColor(adjust(base, 0.4f))
            }
            "Save" -> {
                if (state.hasScript) bg.setColor(base) else bg.setColor(adjust(base, 0.4f))
            }
            else -> bg.setColor(base)
        }
        tv.background = bg
        when (key) {
            "Speed+" -> tv.text = "Speed+ " + String.format("%.2f", state.speed)
            "Speed-" -> tv.text = "Speed- " + String.format("%.2f", state.speed)
            else -> tv.text = key
        }
    }

    private fun adjust(color: Int, f: Float): Int {
        val a = Color.alpha(color)
        val r = (Color.red(color) * f).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * f).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * f).toInt().coerceIn(0, 255)
        return Color.argb(a, r, g, b)
    }

    fun build(actions: Map<String, () -> Unit>, state: MenuState): LinearLayout {
        val root = LinearLayout(context)
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.END
        val outer = dp(8)
        root.setPadding(outer, outer, outer, outer)
        for ((key, action) in actions) {
            val btn = makeButton(key, action)
            decorate(key, btn, state)
            root.addView(btn)
        }
        return root
    }
}
