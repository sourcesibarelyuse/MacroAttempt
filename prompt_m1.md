Write Milestone M1 (Geometry Core) in PURE Kotlin: zero Android imports, zero external deps, ONE file. Include:
- data classes SampledPoint/Stroke/GesturePlan with init validation (1..10 strokes, duration 1..60000, monotonic t)
- TimingConstants interface + defaults (tap 100ms, longPress 500ms, doubleTap 300ms)
- CoordinateResolver (clamp [0,1], typed MissingArgumentError on null, scale to pixels)
- DurationCalculator (len/(speed*density)*1000, typed InvalidSpeedError if <=0)
- GestureBuilders for tap/longPress/doubleTap/swipe/pinchIn/pinchOut per the reference (tap held tapTimeout/2, longPress +50ms, pinch = two symmetric strokes about midpoint)
- PathSampler doing arc-length polyline sampling (no Android Path)
- fun main() with table-driven check() tests covering clamp boundaries, missing-arg throw, zero-length swipe throw, tap duration, swipe duration math, pinch symmetry, sampler midpoint, degenerate pinch. Print "ALL M1 TESTS PASSED" at the end. Must compile standalone with kotlinc.