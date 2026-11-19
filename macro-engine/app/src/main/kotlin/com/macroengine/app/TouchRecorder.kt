package com.macroengine.app

import android.content.Context
import android.view.MotionEvent
import android.view.View

class CaptureView(
    context: Context,
    private val onDown: (x: Int, y: Int) -> Unit,
    private val onUp: (x: Int, y: Int) -> Unit
) : View(context) {

    init {
        setBackgroundColor(0x00000000)
        isClickable = true
        isFocusable = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onDown(event.rawX.toInt(), event.rawY.toInt())
                addPointFromTouch(event.rawX, event.rawY)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                onUp(event.rawX.toInt(), event.rawY.toInt())
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                invalidate()
                return true
            }
        }
        return true
    }
}

object RecorderState {
    var recording: Boolean = false
    var points: MutableList<Pair<Int, Int>> = mutableListOf()
    var screenW: Int = 0
    var screenH: Int = 0
}

fun addPointFromTouch(rawX: Float, rawY: Float) {
    val w = if (RecorderState.screenW > 0) RecorderState.screenW else Int.MAX_VALUE
    val h = if (RecorderState.screenH > 0) RecorderState.screenH else Int.MAX_VALUE
    val x = rawX.toInt().coerceIn(0, if (w == Int.MAX_VALUE) Int.MAX_VALUE else w - 1)
    val y = rawY.toInt().coerceIn(0, if (h == Int.MAX_VALUE) Int.MAX_VALUE else h - 1)
    RecorderState.points.add(Pair(x, y))
}
