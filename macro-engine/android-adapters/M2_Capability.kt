package com.macroengine.adapters

enum class InjectionResult {
    OK,
    FAIL_NOT_AVAILABLE,
    FAIL_REJECTED_CONSTRAINT,
    FAIL_CANCELLED,
    FAIL_SERVICE_LOST,
    FAIL_TRANSPORT
}

enum class Capability {
    NATIVE_GESTURE_DISPATCH,
    MULTI_DISPLAY,
    ROOT_INJECT,
    LEGACY_INPUT
}

enum class InjectorKind {
    NATIVE,
    LEGACY,
    ROOT,
    UNSUPPORTED
}

data class CapabilityReport(
    val sdkInt: Int,
    val serviceConnected: Boolean,
    val rootAvailable: Boolean,
    val privilegedSigned: Boolean
) {
    val canDispatchGesture: Boolean
        get() = sdkInt >= 24 && serviceConnected

    val supportsMultiDisplay: Boolean
        get() = sdkInt >= 30

    val displayManagerApi17: Boolean
        get() = sdkInt >= 17
}

fun probe(r: CapabilityReport): Set<Capability> {
    val caps = mutableSetOf<Capability>()
    if (r.canDispatchGesture) caps.add(Capability.NATIVE_GESTURE_DISPATCH)
    if (r.supportsMultiDisplay) caps.add(Capability.MULTI_DISPLAY)
    if (r.rootAvailable) caps.add(Capability.ROOT_INJECT)
    if (r.sdkInt < 24 && r.privilegedSigned) caps.add(Capability.LEGACY_INPUT)
    return caps
}

fun selectInjector(r: CapabilityReport): InjectorKind = when {
    r.canDispatchGesture -> InjectorKind.NATIVE
    r.rootAvailable -> InjectorKind.ROOT
    r.sdkInt < 24 && r.privilegedSigned -> InjectorKind.LEGACY
    else -> InjectorKind.UNSUPPORTED
}

private var failures = 0

private fun check(name: String, cond: Boolean) {
    if (cond) {
        println("PASS: $name")
    } else {
        failures++
        println("FAIL: $name")
    }
}

fun main() {
    // API 24+ with service -> NATIVE
    run {
        val r = CapabilityReport(sdkInt = 24, serviceConnected = true, rootAvailable = false, privilegedSigned = false)
        check("API24+service canDispatchGesture", r.canDispatchGesture)
        check("API24+service -> NATIVE", selectInjector(r) == InjectorKind.NATIVE)
        check("API24+service has NATIVE_GESTURE_DISPATCH", probe(r).contains(Capability.NATIVE_GESTURE_DISPATCH))
    }

    // API 23 with service -> not NATIVE
    run {
        val r = CapabilityReport(sdkInt = 23, serviceConnected = true, rootAvailable = false, privilegedSigned = false)
        check("API23+service !canDispatchGesture", !r.canDispatchGesture)
        check("API23+service no NATIVE_GESTURE_DISPATCH", !probe(r).contains(Capability.NATIVE_GESTURE_DISPATCH))
        check("API23+service no privilege -> UNSUPPORTED", selectInjector(r) == InjectorKind.UNSUPPORTED)
    }

    // API 30 adds MULTI_DISPLAY
    run {
        val r30 = CapabilityReport(sdkInt = 30, serviceConnected = true, rootAvailable = false, privilegedSigned = false)
        val r29 = CapabilityReport(sdkInt = 29, serviceConnected = true, rootAvailable = false, privilegedSigned = false)
        check("API30 supportsMultiDisplay", r30.supportsMultiDisplay)
        check("API30 has MULTI_DISPLAY", probe(r30).contains(Capability.MULTI_DISPLAY))
        check("API29 no MULTI_DISPLAY", !probe(r29).contains(Capability.MULTI_DISPLAY))
    }

    // Root fallback when no service
    run {
        val r = CapabilityReport(sdkInt = 28, serviceConnected = false, rootAvailable = true, privilegedSigned = false)
        check("no service + root -> ROOT", selectInjector(r) == InjectorKind.ROOT)
        check("root adds ROOT_INJECT", probe(r).contains(Capability.ROOT_INJECT))
    }

    // No service + no root + no privilege -> UNSUPPORTED
    run {
        val r = CapabilityReport(sdkInt = 28, serviceConnected = false, rootAvailable = false, privilegedSigned = false)
        check("no service/root/priv -> UNSUPPORTED", selectInjector(r) == InjectorKind.UNSUPPORTED)
    }

    // Legacy fallback: API<24 + privileged
    run {
        val r = CapabilityReport(sdkInt = 21, serviceConnected = false, rootAvailable = false, privilegedSigned = true)
        check("API21+priv -> LEGACY", selectInjector(r) == InjectorKind.LEGACY)
        check("API21+priv has LEGACY_INPUT", probe(r).contains(Capability.LEGACY_INPUT))
    }

    // API 17 vs 16 displayManager flag
    run {
        val r17 = CapabilityReport(sdkInt = 17, serviceConnected = false, rootAvailable = false, privilegedSigned = false)
        val r16 = CapabilityReport(sdkInt = 16, serviceConnected = false, rootAvailable = false, privilegedSigned = false)
        check("API17 displayManagerApi17", r17.displayManagerApi17)
        check("API16 !displayManagerApi17", !r16.displayManagerApi17)
    }

    // Priority: NATIVE > ROOT
    run {
        val r = CapabilityReport(sdkInt = 30, serviceConnected = true, rootAvailable = true, privilegedSigned = true)
        check("NATIVE beats ROOT", selectInjector(r) == InjectorKind.NATIVE)
    }

    if (failures == 0) {
        println("ALL M2A TESTS PASSED")
    } else {
        println("$failures M2A TEST(S) FAILED")
        throw AssertionError("$failures M2A test(s) failed")
    }
}