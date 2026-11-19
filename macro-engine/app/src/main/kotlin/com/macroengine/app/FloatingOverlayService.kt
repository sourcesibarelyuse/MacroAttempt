package com.macroengine.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.hypot
import com.macroengine.dsl.FlowIr

class FloatingOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var buttonView: View
    private lateinit var menuView: LinearLayout
    private lateinit var buttonParams: WindowManager.LayoutParams
    private lateinit var menuParams: WindowManager.LayoutParams

    private var menuVisible = false
    private var isRecording = false

    // Drag tracking
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var touchDownTime = 0L
    private var pointerDownX = 0f
    private var pointerDownY = 0f
    private val dragThresholdPx = 20f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission required", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        addFloatingButton()
        addPopupMenu()
        return START_STICKY
    }

    // ---------------------------------------------------------------------
    // Floating button
    // ---------------------------------------------------------------------

    private fun addFloatingButton() {
        val sizePx = dp(56)

        // Circular colored button drawn programmatically
        buttonView = object : View(this) {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#FF3F51B5")
            }

            override fun onDraw(canvas: Canvas) {
                val cx = width / 2f
                val cy = height / 2f
                val r = (minOf(width, height) / 2f) - dp(2).toFloat()
                paint.color = if (isRecording) Color.parseColor("#FFD32F2F")
                              else Color.parseColor("#FF3F51B5")
                canvas.drawCircle(cx, cy, r, paint)
            }
        }
        buttonView.isClickable = true

        buttonParams = WindowManager.LayoutParams(
            sizePx,
            sizePx,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(20)
            y = dp(200)
        }

        buttonView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = buttonParams.x
                    initialY = buttonParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    pointerDownX = event.rawX
                    pointerDownY = event.rawY
                    touchDownTime = System.currentTimeMillis()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    buttonParams.x = initialX + dx.toInt()
                    buttonParams.y = initialY + dy.toInt()
                    try {
                        windowManager.updateViewLayout(buttonView, buttonParams)
                    } catch (_: Exception) {
                    }
                    if (menuVisible) repositionMenu()
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val dist = hypot((event.rawX - pointerDownX).toDouble(),
                                     (event.rawY - pointerDownY).toDouble())
                    val elapsed = System.currentTimeMillis() - touchDownTime
                    if (dist < dragThresholdPx && elapsed < 400) {
                        // Treat as tap
                        toggleMenu()
                    }
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        try {
            windowManager.addView(buttonView, buttonParams)
        } catch (e: Exception) {
            Toast.makeText(this, "Failed to add overlay: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    // ---------------------------------------------------------------------
    // Popup menu
    // ---------------------------------------------------------------------

    private fun addPopupMenu() {
        menuView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#F5FFFFFF"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }

        menuView.addView(makeMenuButton("Play", Color.parseColor("#FF2E7D32")) {
            playCurrent()
        })
        menuView.addView(makeMenuButton("Pause", Color.parseColor("#FFF9A825")) {
            stopPlayback()
        })
        menuView.addView(makeMenuButton(
            if (isRecording) "Stop Rec" else "Add Point",
            Color.parseColor("#FF1565C0")
        ) {
            toggleRecording()
        })
        menuView.addView(makeMenuButton("Loop", Color.parseColor("#FF6A1B9A")) { MacroApp.instance.currentScript?.let { MacroApp.instance.currentScript = it.copy(loopCount = if (it.loopCount > 1) 1 else 99) }; toast("loop toggled") })
        menuView.addView(makeMenuButton("Save", Color.parseColor("#FF00838F")) { MacroApp.instance.currentScript?.let { toast(ScriptCodec.toJson(it).take(40) + "...") } ?: toast("no script") })

        menuParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun makeMenuButton(label: String, color: Int, onClick: () -> Unit): View {
        val tv = TextView(this).apply {
            text = label
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            textSize = 14f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(color)
            }
            setOnClickListener { onClick(); updateMenuLabels() }
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(4)
        tv.layoutParams = lp
        return tv
    }

    private fun toggleMenu() {
        if (menuVisible) hideMenu() else showMenu()
    }

    private fun showMenu() {
        if (menuVisible) return
        repositionMenu()
        try {
            windowManager.addView(menuView, menuParams)
            menuVisible = true
        } catch (_: Exception) {
        }
    }

    private fun hideMenu() {
        if (!menuVisible) return
        try {
            windowManager.removeView(menuView)
        } catch (_: Exception) {
        }
        menuVisible = false
    }

    private fun repositionMenu() {
        val gap = dp(8)
        val btnSize = dp(56)
        val screenWidth = resources.displayMetrics.widthPixels

        // Prefer place to the right of the button, otherwise flip to left
        val estimatedMenuWidth = dp(120)
        menuParams.x = if (buttonParams.x + btnSize + gap + estimatedMenuWidth > screenWidth) {
            (buttonParams.x - estimatedMenuWidth - gap).coerceAtLeast(0)
        } else {
            buttonParams.x + btnSize + gap
        }
        menuParams.y = buttonParams.y
        if (menuVisible) {
            try {
                windowManager.updateViewLayout(menuView, menuParams)
            } catch (_: Exception) {
            }
        }
    }

    private fun updateMenuLabels() {
        val third = menuView.getChildAt(2) as? TextView ?: return
        third.text = if (isRecording) "Stop Rec" else "Add Point"
    }

    // ---------------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------------

    private fun buildScriptFromRecording() {
        val pts = RecorderState.points.toList()
        if (pts.isEmpty()) { toast("No points recorded"); return }
        val steps = pts.map { (x, y) ->
            val n = norm(x, y, RecorderState.screenW, RecorderState.screenH)
            Step(StepType.TAP, n.first, n.second, ms = 0)
        }
        MacroApp.instance.currentScript = Script("recorded", steps, loopCount = 1, speedMult = 1.0)
        toast("Recorded ${pts.size} points")
    }

    private fun playCurrent() {
        val s = MacroApp.instance.currentScript ?: run { toast("No script"); return }
        val ir: FlowIr = ScriptCompiler.compile(s)
        hideMenu()
        Thread { try { MacroApp.instance.coordinator.execute(ir) } catch (e: Exception) { toast("err ${e.message}") } }.start()
    }

    private fun startPlayback() {
        val app = MacroApp.instance ?: run {
            toast("MacroApp not initialized")
            return
        }
        val script = app.currentScript ?: run {
            toast("No script loaded")
            return
        }
        hideMenu()
        Thread {
            try {
                app.coordinator.execute(script)
            } catch (e: Exception) {
                toast("Playback error: ${e.message}")
            }
        }.start()
    }

    private fun stopPlayback() {
        val app = MacroApp.instance
        if (app == null) {
            toast("MacroApp not initialized")
            return
        }
        try {
            app.coordinator.stop()
        } catch (_: Exception) {
        }
        hideMenu()
    }

    private var captureView: CaptureView? = null

    private fun toggleRecording() {
        isRecording = !isRecording
        if (isRecording) {
            RecorderState.recording = true
            RecorderState.points.clear()
            RecorderState.screenW = resources.displayMetrics.widthPixels
            RecorderState.screenH = resources.displayMetrics.heightPixels
            val cv = CaptureView(this, { x, y -> addPointFromTouch(x.toFloat(), y.toFloat()) }, { _, _ -> })
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT)
            try { windowManager.addView(cv, lp); captureView = cv } catch (_: Exception) {}
        } else {
            RecorderState.recording = false
            captureView?.let { try { windowManager.removeView(it) } catch (_: Exception) {} }
            captureView = null
            buildScriptFromRecording()
        }
        // M12 will wire real recording
        toast(if (isRecording) "Recording mode ON" else "Recording mode OFF")
        updateMenuLabels()
        buttonView.invalidate()
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        try {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (menuVisible) windowManager.removeView(menuView)
        } catch (_: Exception) {
        }
        try {
            if (::buttonView.isInitialized) windowManager.removeView(buttonView)
        } catch (_: Exception) {
        }
        menuVisible = false
    }
}