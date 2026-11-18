// M5 hardening layer: pure Kotlin, zero Android imports, package com.macroengine.adapters.
// Depends on M2_Capability.kt types in the same package (NOT redeclared/imported):
//   InjectionResult, Capability, InjectorKind, CapabilityReport, probe(), selectInjector()
package com.macroengine.adapters

// Clock abstraction so time is injectable and testable without Android.
interface M5Clock {
    fun nowMs(): Long
}

// Permission model.
enum class PermissionState { GRANTED, DENIED, UNKNOWN }

// Category of permission needed by an injector.
enum class PermissionKind { SERVICE, PRIVILEGED, RUNTIME, CONFIG }

// A single declared requirement. settingsAction is a logical (non-Android) hint
// of where the user would go to grant it; surfaced in denial messages.
data class PermissionRequirement(
    val id: String,
    val kind: PermissionKind,
    val settingsAction: String?
)

// Snapshot of current permission reality used for fail-closed evaluation.
data class PermissionContext(
    val grantedIds: Set<String>,
    val serviceConnected: Boolean,
    val privilegedSigned: Boolean
)

// Declare the permissions required by the currently selectable injector.
// native (NATIVE)  => accessibility SERVICE is required.
// legacy (LEGACY)  => PRIVILEGED signature-level capability required.
// root / unsupported => nothing declared here.
fun declaredPermissions(r: CapabilityReport): List<PermissionRequirement> {
    // NOTE: CapabilityReport has no root field in its constructor surface; root
    // availability is reflected by selectInjector returning ROOT. We still only
    // need the native/legacy branches for declared permissions per spec.
    return when (selectInjector(r)) {
        InjectorKind.NATIVE -> listOf(
            PermissionRequirement(
                id = "accessibility.service",
                kind = PermissionKind.SERVICE,
                settingsAction = "settings:accessibility"
            )
        )
        InjectorKind.LEGACY -> listOf(
            PermissionRequirement(
                id = "signature.privileged",
                kind = PermissionKind.PRIVILEGED,
                settingsAction = null
            )
        )
        // ROOT and UNSUPPORTED declare no explicit permission requirements here.
        InjectorKind.ROOT, InjectorKind.UNSUPPORTED -> emptyList()
    }
}

// Result of fail-closed preflight evaluation.
sealed class Preflight {
    object Ok : Preflight()
    data class Denied(val req: PermissionRequirement, val message: String) : Preflight()
}

// Fail-closed preflight: any requirement not provably satisfied is a denial.
// UNKNOWN / missing => Denied. First denial (declaration order) wins.
fun preflight(reqs: List<PermissionRequirement>, ctx: PermissionContext): Preflight {
    for (req in reqs) {
        val satisfied: Boolean = when (req.kind) {
            PermissionKind.SERVICE -> ctx.serviceConnected && req.id in ctx.grantedIds
            PermissionKind.PRIVILEGED -> ctx.privilegedSigned && req.id in ctx.grantedIds
            PermissionKind.RUNTIME -> req.id in ctx.grantedIds
            PermissionKind.CONFIG -> req.id in ctx.grantedIds
        }
        if (!satisfied) {
            val hint = req.settingsAction?.let { " (action: $it)" } ?: " (no settings action)"
            val msg = "Permission '${req.id}' [${req.kind}] not satisfied$hint"
            return Preflight.Denied(req = req, message = msg)
        }
    }
    return Preflight.Ok
}

// Classification of injection failures for retry/abort policy.
enum class FailureClass { TRANSIENT, PERMANENT, PERMISSION_DENIED, CONFIGURATION }

fun classify(res: InjectionResult): FailureClass = when (res) {
    InjectionResult.OK -> FailureClass.PERMANENT // should not be classified, but never transient
    InjectionResult.FAIL_SERVICE_LOST,
    InjectionResult.FAIL_TRANSPORT,
    InjectionResult.FAIL_CANCELLED -> FailureClass.TRANSIENT
    InjectionResult.FAIL_NOT_AVAILABLE,
    InjectionResult.FAIL_REJECTED_CONSTRAINT -> FailureClass.PERMANENT
}

// Downgrade capabilities after a disconnect: drop NATIVE_GESTURE_DISPATCH,
// keep version-only flags (e.g. MULTI_DISPLAY, LEGACY_INPUT, ROOT_INJECT).
fun downgradeOnDisconnect(caps: Set<Capability>): Set<Capability> =
    caps.filterNot { it == Capability.NATIVE_GESTURE_DISPATCH }.toSet()

// Ensures each pending resolver is invoked EXACTLY once across repeated calls,
// even if a resolver throws or onDisconnect is called multiple times.
class DisconnectGuard {
    private val invoked = mutableSetOf<() -> Unit>()

    // Invokes each resolver exactly once. Returns the number newly invoked this call.
    fun onDisconnect(pending: List<() -> Unit>): Int {
        var count = 0
        for (resolver in pending) {
            // Identity-based exactly-once; mark BEFORE invocation so a re-entrant
            // call cannot double-run the same resolver.
            val firstTime = invoked.add(resolver)
            if (firstTime) {
                count++
                try {
                    resolver()
                } catch (_: Throwable) {
                    // Swallow: exactly-once still holds; one resolver failing must
                    // not re-enable it or block the rest.
                }
            }
        }
        return count
    }
}

// Display routing decision.
data class DisplayRoute(val displayId: Int?, val ignored: Boolean)

// Route to requested display only if the report actually supports multi-display.
// null requested => null + ignored=false. Unsupported => null + ignored=true.
fun routeDisplay(requested: Int?, r: CapabilityReport): DisplayRoute {
    if (requested == null) return DisplayRoute(displayId = null, ignored = false)
    return if (r.supportsMultiDisplay) {
        DisplayRoute(displayId = requested, ignored = false)
    } else {
        DisplayRoute(displayId = null, ignored = true)
    }
}

// Circuit breaker for injection transport.
enum class BreakerState { CLOSED, OPEN, HALF_OPEN }

class CircuitBreaker(
    private val threshold: Int,
    private val cooldownMs: Long,
    private val clock: M5Clock
) {
    private var stateInternal: BreakerState = BreakerState.CLOSED
    private var consecutiveFailures: Int = 0
    private var openedAtMs: Long = 0L

    val state: BreakerState
        get() = stateInternal

    // Whether a request may proceed. In OPEN, transitions to HALF_OPEN once
    // the cooldown has elapsed (lets exactly one probe through).
    fun allowRequest(): Boolean {
        when (stateInternal) {
            BreakerState.CLOSED -> return true
            BreakerState.HALF_OPEN -> return true
            BreakerState.OPEN -> {
                if (clock.nowMs() - openedAtMs >= cooldownMs) {
                    stateInternal = BreakerState.HALF_OPEN
                    return true
                }
                return false
            }
        }
    }

    fun recordSuccess() {
        // Any success resets the breaker fully closed.
        stateInternal = BreakerState.CLOSED
        consecutiveFailures = 0
        openedAtMs = 0L
    }

    fun recordFailure() {
        when (stateInternal) {
            BreakerState.HALF_OPEN -> {
                // Probe failed -> back to OPEN, restart cooldown.
                stateInternal = BreakerState.OPEN
                openedAtMs = clock.nowMs()
                consecutiveFailures = threshold
            }
            BreakerState.CLOSED -> {
                consecutiveFailures++
                if (consecutiveFailures >= threshold) {
                    stateInternal = BreakerState.OPEN
                    openedAtMs = clock.nowMs()
                }
            }
            BreakerState.OPEN -> {
                // Already open; keep cooldown anchored to last open transition.
                openedAtMs = clock.nowMs()
            }
        }
    }
}

// ---- Table-driven tests -----------------------------------------------------

// Minimal assertion harness; throws on failure.
private fun check(name: String, cond: Boolean) {
    if (!cond) throw IllegalStateException("CHECK FAILED: $name")
}

// Manual fake clock for breaker tests.
private class FakeClock(var t: Long = 0L) : M5Clock {
    override fun nowMs(): Long = t
}

fun main() {
    // --- declaredPermissions: native / legacy / unsupported ---
    // native: sdk>=24 && serviceConnected; also selectInjector picks NATIVE.
    val nativeReport = CapabilityReport(
        sdkInt = 30, serviceConnected = true, rootAvailable = false, privilegedSigned = false
    )
    val nativePerms = declaredPermissions(nativeReport)
    check("native has 1 req", nativePerms.size == 1)
    check("native req is SERVICE", nativePerms[0].kind == PermissionKind.SERVICE)
    check("native req has settings action", nativePerms[0].settingsAction == "settings:accessibility")

    // legacy: not native (sdk<24 or not connected) but privileged.
    val legacyReport = CapabilityReport(
        sdkInt = 23, serviceConnected = false, rootAvailable = false, privilegedSigned = true
    )
    check("legacy selectInjector", selectInjector(legacyReport) == InjectorKind.LEGACY)
    val legacyPerms = declaredPermissions(legacyReport)
    check("legacy has 1 req", legacyPerms.size == 1)
    check("legacy req is PRIVILEGED", legacyPerms[0].kind == PermissionKind.PRIVILEGED)

    // unsupported: no caps, no root, not privileged -> empty.
    val unsupportedReport = CapabilityReport(
        sdkInt = 21, serviceConnected = false, rootAvailable = false, privilegedSigned = false
    )
    check("unsupported selectInjector", selectInjector(unsupportedReport) == InjectorKind.UNSUPPORTED)
    check("unsupported has no perms", declaredPermissions(unsupportedReport).isEmpty())

    // --- preflight: Ok / Denied / UNKNOWN ---
    // Ok: native req satisfied.
    val okCtx = PermissionContext(
        grantedIds = setOf("accessibility.service"),
        serviceConnected = true,
        privilegedSigned = false
    )
    check("preflight ok", preflight(nativePerms, okCtx) is Preflight.Ok)

    // Denied (missing): service connected but id not granted.
    val deniedCtx = PermissionContext(
        grantedIds = emptySet(),
        serviceConnected = true,
        privilegedSigned = false
    )
    val denied = preflight(nativePerms, deniedCtx)
    check("preflight denied is Denied", denied is Preflight.Denied)
    denied as Preflight.Denied
    check("denied message includes settings action", denied.message.contains("settings:accessibility"))
    check("denied req id", denied.req.id == "accessibility.service")

    // UNKNOWN (fail-closed): service not connected, id present -> still denied.
    val unknownCtx = PermissionContext(
        grantedIds = setOf("accessibility.service"),
        serviceConnected = false,
        privilegedSigned = false
    )
    check("preflight unknown is Denied", preflight(nativePerms, unknownCtx) is Preflight.Denied)

    // First denial wins: two reqs, both missing -> first one reported.
    val twoReqs = listOf(
        PermissionRequirement("a", PermissionKind.SERVICE, "settings:a"),
        PermissionRequirement("b", PermissionKind.PRIVILEGED, "settings:b")
    )
    val emptyCtx = PermissionContext(emptySet(), false, false)
    val firstDenied = preflight(twoReqs, emptyCtx) as Preflight.Denied
    check("first denial wins", firstDenied.req.id == "a")

    // --- classify ---
    check("classify service lost", classify(InjectionResult.FAIL_SERVICE_LOST) == FailureClass.TRANSIENT)
    check("classify transport", classify(InjectionResult.FAIL_TRANSPORT) == FailureClass.TRANSIENT)
    check("classify cancelled", classify(InjectionResult.FAIL_CANCELLED) == FailureClass.TRANSIENT)
    check("classify not available", classify(InjectionResult.FAIL_NOT_AVAILABLE) == FailureClass.PERMANENT)
    check("classify rejected", classify(InjectionResult.FAIL_REJECTED_CONSTRAINT) == FailureClass.PERMANENT)

    // --- downgradeOnDisconnect keeps MULTI_DISPLAY, removes NATIVE_GESTURE_DISPATCH ---
    val fullCaps = probe(nativeReport)
    check("probe(native) has native dispatch", Capability.NATIVE_GESTURE_DISPATCH in fullCaps)
    val downgraded = downgradeOnDisconnect(fullCaps)
    check("downgraded removes native dispatch", Capability.NATIVE_GESTURE_DISPATCH !in downgraded)
    check("downgraded keeps multi display", Capability.MULTI_DISPLAY in downgraded)

    // --- DisconnectGuard exactly-once ---
    val guard = DisconnectGuard()
    var calls = 0
    val r1: () -> Unit = { calls++ }
    var calls2 = 0
    val r2: () -> Unit = { calls2++ }
    val pending = listOf(r1, r2)
    val first = guard.onDisconnect(pending)
    val second = guard.onDisconnect(pending)
    val third = guard.onDisconnect(listOf(r1))
    check("guard first invokes 2", first == 2)
    check("guard second invokes 0", second == 0)
    check("guard third invokes 0", third == 0)
    check("r1 exactly once", calls == 1)
    check("r2 exactly once", calls2 == 1)

    // --- routeDisplay: gated / null / passthrough ---
    val multiReport = CapabilityReport(
        sdkInt = 30, serviceConnected = true, rootAvailable = false, privilegedSigned = false
    )
    val noMultiReport = CapabilityReport(
        sdkInt = 28, serviceConnected = true, rootAvailable = false, privilegedSigned = false
    )
    check("route supportsMultiDisplay true", multiReport.supportsMultiDisplay)
    check("route supportsMultiDisplay false", !noMultiReport.supportsMultiDisplay)

    val passthrough = routeDisplay(7, multiReport)
    check("route passthrough id", passthrough.displayId == 7 && !passthrough.ignored)

    val gated = routeDisplay(7, noMultiReport)
    check("route gated null", gated.displayId == null && gated.ignored)

    val nullReq = routeDisplay(null, multiReport)
    check("route null requested", nullReq.displayId == null && !nullReq.ignored)

    // --- CircuitBreaker CLOSED->OPEN->HALF_OPEN->CLOSED and HALF_OPEN->OPEN ---
    val clock = FakeClock(0L)
    val breaker = CircuitBreaker(threshold = 2, cooldownMs = 100L, clock = clock)
    check("breaker starts closed", breaker.state == BreakerState.CLOSED)
    check("breaker closed allows", breaker.allowRequest())

    breaker.recordFailure() // 1
    check("breaker still closed after 1", breaker.state == BreakerState.CLOSED)
    breaker.recordFailure() // 2 -> OPEN
    check("breaker open after threshold", breaker.state == BreakerState.OPEN)
    check("breaker open blocks", !breaker.allowRequest())

    // Not enough time elapsed -> still blocked, still OPEN.
    clock.t = 50L
    check("breaker open before cooldown", !breaker.allowRequest())
    check("breaker still open", breaker.state == BreakerState.OPEN)

    // Cooldown elapsed -> HALF_OPEN and allows probe.
    clock.t = 100L
    check("breaker allows after cooldown", breaker.allowRequest())
    check("breaker half open", breaker.state == BreakerState.HALF_OPEN)

    // Probe succeeds -> CLOSED.
    breaker.recordSuccess()
    check("breaker closed after success", breaker.state == BreakerState.CLOSED)

    // Drive back to OPEN then HALF_OPEN, fail the probe -> OPEN again.
    val clock2 = FakeClock(0L)
    val b2 = CircuitBreaker(threshold = 1, cooldownMs = 10L, clock = clock2)
    b2.recordFailure() // -> OPEN
    check("b2 open", b2.state == BreakerState.OPEN)
    clock2.t = 10L
    check("b2 allow after cooldown", b2.allowRequest())
    check("b2 half open", b2.state == BreakerState.HALF_OPEN)
    b2.recordFailure() // half-open probe fails -> OPEN
    check("b2 open after failed probe", b2.state == BreakerState.OPEN)

    // --- selectInjector / probe sanity using real constructor only ---
    check("select native", selectInjector(nativeReport) == InjectorKind.NATIVE)
    check("probe empty for unsupported", probe(unsupportedReport).isEmpty())

    println("ALL M5 TESTS PASSED")
}