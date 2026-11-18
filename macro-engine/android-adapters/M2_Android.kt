package com.macroengine.adapters

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Build
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

import com.macroengine.core.GesturePlan
import com.macroengine.core.Stroke
import com.macroengine.core.SampledPoint

// ---------------------------------------------------------------------------
// Result types
// ---------------------------------------------------------------------------



// ---------------------------------------------------------------------------
// ServiceRegistry
// ---------------------------------------------------------------------------

object ServiceRegistry {

    @Volatile
    private var active: MacroAccessibilityService? = null

    private val observers = CopyOnWriteArrayList<MacroAccessibilityServiceObserver>()
    private val connectedFlag = AtomicBoolean(false)

    fun onServiceConnected(service: MacroAccessibilityService) {
        active = service
        connectedFlag.set(true)
        for (observer in observers) {
            try {
                observer.onServiceConnected(service)
            } catch (_: Throwable) {
                // observers must not break the registry
            }
        }
    }

    fun onServiceDisconnected(service: MacroAccessibilityService) {
        // Notify observers BEFORE clearing state.
        for (observer in observers) {
            try {
                observer.onServiceDisconnected(service)
            } catch (_: Throwable) {
                // observers must not break the registry
            }
        }
        if (active === service) {
            active = null
        }
        connectedFlag.set(false)
    }

    fun isServiceConnected(): Boolean = connectedFlag.get() && active != null

    fun getActiveService(): MacroAccessibilityService? = active

    fun subscribe(observer: MacroAccessibilityServiceObserver) {
        observers.addIfAbsent(observer)
    }

    fun unsubscribe(observer: MacroAccessibilityServiceObserver) {
        observers.remove(observer)
    }
}

interface MacroAccessibilityServiceObserver {
    fun onServiceConnected(service: MacroAccessibilityService)
    fun onServiceDisconnected(service: MacroAccessibilityService)
}

// ---------------------------------------------------------------------------
// Accessibility service
// ---------------------------------------------------------------------------

class MacroAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        ServiceRegistry.onServiceConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op; gesture injection only.
    }

    override fun onInterrupt() {
        // No-op.
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        ServiceRegistry.onServiceDisconnected(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        ServiceRegistry.onServiceDisconnected(this)
        super.onDestroy()
    }
}

// ---------------------------------------------------------------------------
// Injector interface
// ---------------------------------------------------------------------------

interface GestureInjector {
    suspend fun inject(plan: GesturePlan, displayId: Int?): InjectionResult
}

// ---------------------------------------------------------------------------
// Native gesture injector
// ---------------------------------------------------------------------------

class NativeGestureInjector : GestureInjector {

    companion object {
        const val MAX_DURATION_MS = 60_000L
        const val MAX_STROKES = 10
    }

    // Keyed by the built GestureDescription to correlate with accessibility callbacks.
    private val pending = ConcurrentHashMap<GestureDescription, CancellableContinuation<InjectionResult>>()

    private val observer = object : MacroAccessibilityServiceObserver {
        override fun onServiceConnected(service: MacroAccessibilityService) {
            // Nothing to do.
        }

        override fun onServiceDisconnected(service: MacroAccessibilityService) {
            failInFlight(InjectionResult.FAIL_SERVICE_LOST)
        }
    }

    init {
        ServiceRegistry.subscribe(observer)
    }

    private fun failInFlight(result: InjectionResult) {
        val keys = pending.keys.toList()
        for (key in keys) {
            val cont = pending.remove(key) ?: continue
            if (cont.isActive) {
                cont.resume(result)
            }
        }
    }

    override suspend fun inject(plan: GesturePlan, displayId: Int?): InjectionResult {
        // Validate constraints BEFORE dispatch.
        if (plan.strokes.size > MAX_STROKES) {
            return InjectionResult.FAIL_REJECTED_CONSTRAINT
        }

        val builder = GestureDescription.Builder()

        if (Build.VERSION.SDK_INT >= 30 && displayId != null) {
            builder.setDisplayId(displayId)
        }

        var totalDuration = 0L

        for (stroke in plan.strokes) {
            val points: List<SampledPoint> = stroke.points
            if (points.isEmpty()) {
                // An empty stroke cannot be built; reject the whole plan.
                return InjectionResult.FAIL_REJECTED_CONSTRAINT
            }

            val path = Path()
            val first = points.first()
            path.moveTo(first.x.toFloat(), first.y.toFloat())
            for (i in 1 until points.size) {
                val p = points[i]
                path.lineTo(p.x.toFloat(), p.y.toFloat())
            }
            // If a stroke has a single point, add a tiny line so Path is valid.
            if (points.size == 1) {
                path.lineTo(first.x.toFloat() + 0.01f, first.y.toFloat() + 0.01f)
            }

            val startTime = stroke.start.t
            val duration = stroke.durationMs

            if (duration <= 0L) {
                return InjectionResult.FAIL_REJECTED_CONSTRAINT
            }

            val endTime = startTime + duration
            if (endTime > totalDuration) {
                totalDuration = endTime
            }

            val strokeDescription = GestureDescription.StrokeDescription(path, startTime, duration)
            builder.addStroke(strokeDescription)
        }

        if (totalDuration > MAX_DURATION_MS) {
            return InjectionResult.FAIL_REJECTED_CONSTRAINT
        }

        if (!ServiceRegistry.isServiceConnected()) {
            return InjectionResult.FAIL_SERVICE_LOST
        }

        val service = ServiceRegistry.getActiveService()
            ?: return InjectionResult.FAIL_SERVICE_LOST

        val description = try {
            builder.build()
        } catch (_: Throwable) {
            return InjectionResult.FAIL_REJECTED_CONSTRAINT
        }

        return suspendCancellableCoroutine { cont ->
            pending[description] = cont

            cont.invokeOnCancellation {
                pending.remove(description)
            }

            val dispatched = try {
                service.dispatchGesture(
                    description,
                    object : AccessibilityService.GestureResultCallback() {
                        override fun onCompleted(gesture: GestureDescription?) {
                            val target = gesture ?: description
                            val c = pending.remove(target) ?: pending.remove(description)
                            if (c != null && c.isActive) {
                                c.resume(InjectionResult.OK)
                            }
                        }

                        override fun onCancelled(gesture: GestureDescription?) {
                            val target = gesture ?: description
                            val c = pending.remove(target) ?: pending.remove(description)
                            if (c != null && c.isActive) {
                                c.resume(InjectionResult.FAIL_CANCELLED)
                            }
                        }
                    },
                    null
                )
            } catch (_: Throwable) {
                false
            }

            if (!dispatched) {
                pending.remove(description)
                if (cont.isActive) {
                    cont.resume(InjectionResult.FAIL_SERVICE_LOST)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Unsupported stub
// ---------------------------------------------------------------------------

class UnsupportedInjector : GestureInjector {
    override suspend fun inject(plan: GesturePlan, displayId: Int?): InjectionResult =
        InjectionResult.FAIL_NOT_AVAILABLE
}

// ---------------------------------------------------------------------------
// Factory + capability probe
// ---------------------------------------------------------------------------

object InjectorFactory {

    /**
     * Capability probe. Gesture dispatch is supported on API 24+ when an
     * accessibility service is available. On API 30+, per-display targeting
     * is additionally supported.
     */
    fun isSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
    }

    fun create(): GestureInjector {
        return if (isSupported()) NativeGestureInjector() else UnsupportedInjector()
    }
}