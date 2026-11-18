Write the M5 hardening layer, ONE pure Kotlin file (zero Android imports), package com.macroengine.adapters. Output ONLY Kotlin code; explanations in // comments only.
EXACT existing API from M2_Capability.kt (SAME package; do NOT redeclare or import these):
- enum InjectionResult { OK, FAIL_NOT_AVAILABLE, FAIL_REJECTED_CONSTRAINT, FAIL_CANCELLED, FAIL_SERVICE_LOST, FAIL_TRANSPORT }
- enum Capability { NATIVE_GESTURE_DISPATCH, MULTI_DISPLAY, ROOT_INJECT, LEGACY_INPUT }
- enum InjectorKind { NATIVE, LEGACY, ROOT, UNSUPPORTED }
- data class CapabilityReport(sdkInt: Int, serviceConnected: Boolean, rootAvailable: Boolean, privilegedSigned: Boolean) with DERIVED vals canDispatchGesture=(sdkInt>=24 && serviceConnected), supportsMultiDisplay=(sdkInt>=30), displayManagerApi17=(sdkInt>=17). Construct it ONLY with those 4 named/positional args.
- fun probe(r: CapabilityReport): Set<Capability>; fun selectInjector(r: CapabilityReport): InjectorKind.
Define your own interface M5Clock { fun nowMs(): Long }. Include:
- enum PermissionState { GRANTED, DENIED, UNKNOWN }; enum PermissionKind { SERVICE, PRIVILEGED, RUNTIME, CONFIG }
- data class PermissionRequirement(id: String, kind: PermissionKind, settingsAction: String?)
- data class PermissionContext(grantedIds: Set<String>, serviceConnected: Boolean, privilegedSigned: Boolean)
- fun declaredPermissions(r: CapabilityReport): List<PermissionRequirement> (native=>SERVICE accessibility; legacy=>PRIVILEGED; unsupported=>empty)
- sealed class Preflight { object Ok; data class Denied(val req: PermissionRequirement, val message: String) }; fun preflight(reqs, ctx): Preflight fail-closed (UNKNOWN/missing=>Denied, message includes settingsAction, first denial wins)
- enum FailureClass { TRANSIENT, PERMANENT, PERMISSION_DENIED, CONFIGURATION }; fun classify(res: InjectionResult): FailureClass (SERVICE_LOST/TRANSPORT/CANCELLED=>TRANSIENT; NOT_AVAILABLE/REJECTED_CONSTRAINT=>PERMANENT)
- fun downgradeOnDisconnect(caps: Set<Capability>): Set<Capability> removes NATIVE_GESTURE_DISPATCH, keeps version-only flags
- class DisconnectGuard { fun onDisconnect(pending: List<() -> Unit>): Int } invokes each resolver EXACTLY once across repeated calls
- data class DisplayRoute(displayId: Int?, ignored: Boolean); fun routeDisplay(requested: Int?, r: CapabilityReport): DisplayRoute (requested only if r.supportsMultiDisplay else null+ignored=true; null requested=>null+ignored=false)
- enum BreakerState { CLOSED, OPEN, HALF_OPEN }; class CircuitBreaker(threshold: Int, cooldownMs: Long, clock: M5Clock) with recordFailure/recordSuccess/allowRequest/state
- fun main() table-driven check() tests constructing CapabilityReport ONLY via the 4 real params (e.g. CapabilityReport(sdkInt=30, serviceConnected=true, rootAvailable=false, privilegedSigned=false)); use probe()/selectInjector() for capability sets. Test declaredPermissions native/legacy/unsupported; preflight Ok/Denied/UNKNOWN; classify; downgrade keeps MULTI_DISPLAY removes NATIVE_GESTURE_DISPATCH; DisconnectGuard exactly-once; routeDisplay gated/null/passthrough; CircuitBreaker CLOSED->OPEN->HALF_OPEN->CLOSED and HALF_OPEN->OPEN with a manual fake clock. Print "ALL M5 TESTS PASSED".
