Write the M2a capability layer of an Android macro engine in PURE Kotlin (zero Android imports), ONE file, package com.macroengine.adapters. Include:
- enum InjectionResult { OK, FAIL_NOT_AVAILABLE, FAIL_REJECTED_CONSTRAINT, FAIL_CANCELLED, FAIL_SERVICE_LOST, FAIL_TRANSPORT }
- enum Capability { NATIVE_GESTURE_DISPATCH, MULTI_DISPLAY, ROOT_INJECT, LEGACY_INPUT }
- enum InjectorKind { NATIVE, LEGACY, ROOT, UNSUPPORTED }
- data class CapabilityReport(sdkInt: Int, serviceConnected: Boolean, rootAvailable: Boolean, privilegedSigned: Boolean) with derived flags: canDispatchGesture = sdkInt>=24 && serviceConnected; supportsMultiDisplay = sdkInt>=30; displayManagerApi17 = sdkInt>=17
- pure fun probe(r: CapabilityReport): Set<Capability>
- pure fun selectInjector(r: CapabilityReport): InjectorKind with priority: NATIVE if canDispatchGesture; else ROOT if rootAvailable; else LEGACY if sdkInt<24 && privilegedSigned; else UNSUPPORTED
- fun main() with table-driven check() tests covering: API 24+ with service -> NATIVE; API 23 with service -> not NATIVE; API 30 adds MULTI_DISPLAY; root fallback when no service; no service + no root + no privilege -> UNSUPPORTED; API 17 vs 16 displayManager flag. Print "ALL M2A TESTS PASSED" at the end.
