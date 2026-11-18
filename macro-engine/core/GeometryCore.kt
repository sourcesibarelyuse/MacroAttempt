package com.macroengine.core

// M1_GeometryCore.kt — Pure Kotlin geometry core for a gesture engine.
// No Android imports, no external deps. Compile standalone: kotlinc M1_GeometryCore.kt -include-runtime -d m1.jar

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------
// Errors
// ---------------------------------------------------------------------------

/** Base type for all typed errors raised by the geometry core. */
sealed class GeometryError(message: String) : RuntimeException(message)

/** Thrown when a required coordinate argument is absent (null). */
class MissingArgumentError(val argName: String) :
    GeometryError("Missing required coordinate argument: '$argName'")

/** Thrown when a speed value is non-positive (cannot divide/drive motion). */
class InvalidSpeedError(val speed: Double) :
    GeometryError("Invalid speed: $speed (must be > 0)")

/** Thrown when a plan/stroke fails structural validation. */
class PlanValidationError(message: String) : GeometryError(message)

// ---------------------------------------------------------------------------
// Core data model
// ---------------------------------------------------------------------------

/** A single sample on a stroke polyline. Coordinates are in normalized [0,1]. */
data class SampledPoint(
    val x: Double,
    val y: Double,
    val t: Long,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "SampledPoint coords must be finite" }
        require(t >= 0L) { "SampledPoint.t must be >= 0, was $t" }
    }
}

/**
 * A stroke is an ordered, time-monotonic polyline of >= 1 point.
 * Invariant: points[i].t is non-decreasing across the whole stroke.
 */
data class Stroke(val points: List<SampledPoint>) {
    init {
        require(points.isNotEmpty()) { "Stroke must have at least one point" }
        for (i in 1 until points.size) {
            val prev = points[i - 1].t
            val cur = points[i].t
            require(cur >= prev) {
                "Stroke timing must be monotonic: point[$i].t=$cur < point[${i - 1}].t=$prev"
            }
        }
    }

    val start: SampledPoint get() = points.first()
    val end: SampledPoint get() = points.last()
    val durationMs: Long get() = end.t - start.t
}

/**
 * A gesture plan: 1..10 strokes, total duration in [1, 60000] ms,
 * and every stroke time-monotonic (enforced by Stroke).
 * Stroke time is absolute ms within the plan.
 */
data class GesturePlan(val strokes: List<Stroke>) {
    init {
        require(strokes.size in 1..10) { "GesturePlan requires 1..10 strokes, got ${strokes.size}" }
        val minT = strokes.minOf { it.start.t }
        val maxT = strokes.maxOf { it.end.t }
        require(minT >= 0L) { "GesturePlan earliest point must be >= 0 ms, got $minT" }
        val duration = maxT - minT
        require(duration in 1L..60000L) { "GesturePlan duration must be 1..60000 ms, got $duration" }
    }

    val durationMs: Long get() = strokes.maxOf { it.end.t } - strokes.minOf { it.start.t }
}

// ---------------------------------------------------------------------------
// Timing configuration
// ---------------------------------------------------------------------------

/** Timing parameters (ms) used by gesture builders. */
interface TimingConstants {
    val tapTimeoutMs: Long
    val longPressTimeoutMs: Long
    val doubleTapTimeoutMs: Long

    /** Defaults per reference: tap 100, longPress 500, doubleTap 300. */
    data class Defaults(
        override val tapTimeoutMs: Long = 100L,
        override val longPressTimeoutMs: Long = 500L,
        override val doubleTapTimeoutMs: Long = 300L,
    ) : TimingConstants {
        init {
            require(tapTimeoutMs > 0) { "tapTimeoutMs must be > 0" }
            require(longPressTimeoutMs > 0) { "longPressTimeoutMs must be > 0" }
            require(doubleTapTimeoutMs > 0) { "doubleTapTimeoutMs must be > 0" }
        }
    }
}

// ---------------------------------------------------------------------------
// CoordinateResolver
// ---------------------------------------------------------------------------

/**
 * Resolves normalized [0,1] coordinates to pixel space for a given viewport,
 * clamping out-of-range values. Null inputs raise a typed MissingArgumentError.
 */
class CoordinateResolver(
    val widthPx: Double,
    val heightPx: Double,
) {
    init {
        require(widthPx > 0 && heightPx > 0) { "Viewport dims must be > 0" }
    }

    fun clamp01(v: Double): Double = when {
        v.isNaN() -> 0.0
        v < 0.0 -> 0.0
        v > 1.0 -> 1.0
        else -> v
    }

    /** Clamps then scales a normalized X to pixels. Throws if x is null. */
    fun resolveX(x: Double?): Double =
        clamp01(x ?: throw MissingArgumentError("x")) * widthPx

    /** Clamps then scales a normalized Y to pixels. Throws if y is null. */
    fun resolveY(y: Double?): Double =
        clamp01(y ?: throw MissingArgumentError("y")) * heightPx

    fun resolve(x: Double?, y: Double?): Pair<Double, Double> = resolveX(x) to resolveY(y)
}

// ---------------------------------------------------------------------------
// DurationCalculator
// ---------------------------------------------------------------------------

/**
 * Computes stroke durations from path length, speed, and sampling density.
 * durationMs = len / (speed * density) * 1000
 */
class DurationCalculator(
    val speed: Double,
    val density: Double = 1.0,
) {
    init {
        if (speed <= 0.0 || !speed.isFinite()) throw InvalidSpeedError(speed)
        require(density > 0.0 && density.isFinite()) { "density must be > 0, got $density" }
    }

    fun durationMs(length: Double): Long {
        require(length >= 0.0) { "length must be >= 0, got $length" }
        val seconds = length / (speed * density)
        return Math.round(seconds * 1000.0).coerceAtLeast(1L)
    }

    companion object {
        fun straightLength(ax: Double, ay: Double, bx: Double, by: Double): Double =
            hypot(bx - ax, by - ay)
    }
}

// ---------------------------------------------------------------------------
// GestureBuilders
// ---------------------------------------------------------------------------

/**
 * Factory for standard gestures. All coordinates normalized [0,1].
 * Durations follow the reference: tap held for tapTimeout/2; longPress adds
 * 50 ms over longPressTimeout; pinches are two symmetric strokes about the midpoint.
 */
class GestureBuilders(private val timing: TimingConstants = TimingConstants.Defaults()) {

    /** Tap: press+release at a point, held for tapTimeout/2 -> total duration = tapTimeout/2. */
    fun tap(x: Double, y: Double, startMs: Long = 0L): GesturePlan {
        val held = timing.tapTimeoutMs / 2
        val pts = listOf(
            SampledPoint(x, y, startMs),
            SampledPoint(x, y, startMs + held),
        )
        return GesturePlan(listOf(Stroke(pts)))
    }

    /** Long press: held for longPressTimeout + 50 ms. */
    fun longPress(x: Double, y: Double, startMs: Long = 0L): GesturePlan {
        val held = timing.longPressTimeoutMs + 50L
        val pts = listOf(
            SampledPoint(x, y, startMs),
            SampledPoint(x, y, startMs + held),
        )
        return GesturePlan(listOf(Stroke(pts)))
    }

    /** Double tap: two taps separated by the doubleTap timeout, compactly timed. */
    fun doubleTap(x: Double, y: Double, startMs: Long = 0L): GesturePlan {
        val held = timing.tapTimeoutMs / 2
        val gap = timing.doubleTapTimeoutMs
        val t0 = startMs
        val t1 = t0 + held
        val t2 = t1 + gap
        val t3 = t2 + held
        val stroke1 = Stroke(listOf(SampledPoint(x, y, t0), SampledPoint(x, y, t1)))
        val stroke2 = Stroke(listOf(SampledPoint(x, y, t2), SampledPoint(x, y, t3)))
        return GesturePlan(listOf(stroke1, stroke2))
    }

    /** Swipe from (x0,y0) to (x1,y1). Duration derived from straight-line length. */
    fun swipe(
        x0: Double, y0: Double,
        x1: Double, y1: Double,
        speed: Double,
        density: Double = 1.0,
        startMs: Long = 0L,
    ): GesturePlan {
        val len = DurationCalculator.straightLength(x0, y0, x1, y1)
        if (len <= 0.0) {
            throw PlanValidationError("Zero-length swipe is not a valid gesture")
        }
        val dur = DurationCalculator(speed, density).durationMs(len)
        val pts = listOf(
            SampledPoint(x0, y0, startMs),
            SampledPoint(x1, y1, startMs + dur),
        )
        return GesturePlan(listOf(Stroke(pts)))
    }

    /** Pinch-in: two fingers move symmetrically toward the midpoint (cx,cy). */
    fun pinchIn(
        cx: Double, cy: Double, radius: Double,
        speed: Double, density: Double = 1.0,
        startMs: Long = 0L,
    ): GesturePlan = pinch(cx, cy, radius, 0.0, speed, density, startMs)

    /** Pinch-out: two fingers move symmetrically away from the midpoint (cx,cy). */
    fun pinchOut(
        cx: Double, cy: Double, radius: Double,
        speed: Double, density: Double = 1.0,
        startMs: Long = 0L,
    ): GesturePlan = pinch(cx, cy, radius, radius * 2.0, speed, density, startMs)

    /**
     * Two symmetric strokes about (cx,cy). Each finger starts at radius from
     * center along the x axis (left finger at -x, right at +x) and travels a
     * path length of |targetRadius - radius| toward/away from the center.
     */
    private fun pinch(
        cx: Double, cy: Double, radius: Double, targetRadius: Double,
        speed: Double, density: Double, startMs: Long,
    ): GesturePlan {
        require(radius >= 0.0) { "pinch radius must be > 0, got $radius" }
        require(targetRadius >= 0.0) { "targetRadius must be >= 0, got $targetRadius" }

        val travel = abs(targetRadius - radius)
        val dur = if (travel == 0.0) {
            // Degenerate pinch: still a valid plan, use a minimal hold.
            (timing.tapTimeoutMs / 2)
        } else {
            DurationCalculator(speed, density).durationMs(travel)
        }

        val leftStart = SampledPoint(cx - radius, cy, startMs)
        val leftEnd = SampledPoint(cx - targetRadius, cy, startMs + dur)
        val rightStart = SampledPoint(cx + radius, cy, startMs)
        val rightEnd = SampledPoint(cx + targetRadius, cy, startMs + dur)

        return GesturePlan(
            listOf(
                Stroke(listOf(leftStart, leftEnd)),
                Stroke(listOf(rightStart, rightEnd)),
            ),
        )
    }
}

// ---------------------------------------------------------------------------
// PathSampler — arc-length polyline sampling (no Android Path).
// ---------------------------------------------------------------------------

/** A resampled point on a polyline at a given arc length from the start. */
data class ArcSample(val x: Double, val y: Double, val arcLength: Double)

object PathSampler {

    /** Cumulative arc length of a polyline at each vertex. */
    fun cumulativeLengths(points: List<SampledPoint>): DoubleArray {
        val out = DoubleArray(points.size)
        var acc = 0.0
        for (i in 1 until points.size) {
            acc += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
            out[i] = acc
        }
        return out
    }

    fun totalLength(points: List<SampledPoint>): Double {
        val c = cumulativeLengths(points)
        return if (c.isEmpty()) 0.0 else c.last()
    }

    /** Returns the point at a given arc length (clamped to [0, totalLength]). */
    fun pointAtArcLength(points: List<SampledPoint>, arcLength: Double): ArcSample {
        require(points.isNotEmpty()) { "Cannot sample empty polyline" }
        if (points.size == 1) return ArcSample(points[0].x, points[0].y, 0.0)

        val cum = cumulativeLengths(points)
        val total = cum.last()
        val target = arcLength.coerceIn(0.0, total)
        if (target <= 0.0) return ArcSample(points[0].x, points[0].y, 0.0)
        if (target >= total) return ArcSample(points.last().x, points.last().y, total)

        // Binary search for the segment containing target.
        var lo = 0
        var hi = points.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cum[mid] < target) lo = mid + 1 else hi = mid
        }
        val i = lo
        val segStart = cum[i - 1]
        val segLen = cum[i] - segStart
        val frac = if (segLen == 0.0) 0.0 else (target - segStart) / segLen
        val p0 = points[i - 1]
        val p1 = points[i]
        val x = p0.x + (p1.x - p0.x) * frac
        val y = p0.y + (p1.y - p0.y) * frac
        return ArcSample(x, y, target)
    }

    /** Samples the polyline at `count` evenly spaced arc-length positions (inclusive). */
    fun sampleEvenly(points: List<SampledPoint>, count: Int): List<ArcSample> {
        require(count >= 2) { "count must be >= 2, got $count" }
        val total = totalLength(points)
        if (total == 0.0) {
            val p = points[0]
            return (0 until count).map { ArcSample(p.x, p.y, 0.0) }
        }
        val step = total / (count - 1)
        return (0 until count).map { i -> pointAtArcLength(points, i * step) }
    }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

private var testsRun = 0
private var testsPassed = 0
private var testsFailed = 0

private fun check(name: String, condition: Boolean) {
    testsRun++
    if (condition) {
        testsPassed++
    } else {
        testsFailed++
        System.err.println("FAIL: $name")
    }
}

private fun checkApprox(name: String, actual: Double, expected: Double, eps: Double = 1e-9) {
    check("$name (actual=$actual expected=$expected)", abs(actual - expected) <= eps)
}

private fun checkThrows(name: String, block: () -> Unit, typeCheck: (Throwable) -> Boolean) {
    testsRun++
    try {
        block()
        testsFailed++
        System.err.println("FAIL: $name — expected throw, none happened")
    } catch (t: Throwable) {
        if (typeCheck(t)) testsPassed++
        else {
            testsFailed++
            System.err.println("FAIL: $name — wrong throw type ${t::class.simpleName}: ${t.message}")
        }
    }
}

private fun testClampBoundaries() {
    val r = CoordinateResolver(1000.0, 500.0)
    checkApprox("clamp below 0 x", r.resolveX(-0.5), 0.0)
    checkApprox("clamp above 1 x", r.resolveX(1.5), 1000.0)
    checkApprox("exact 0 x", r.resolveX(0.0), 0.0)
    checkApprox("exact 1 x", r.resolveX(1.0), 1000.0)
    checkApprox("mid x", r.resolveX(0.5), 500.0)
    checkApprox("clamp NaN -> 0", r.resolveX(Double.NaN), 0.0)
    checkApprox("clamp below 0 y", r.resolveY(-0.2), 0.0)
    checkApprox("clamp above 1 y", r.resolveY(2.0), 500.0)
    checkApprox("mid y", r.resolveY(0.5), 250.0)

    // Clamp is idempotent.
    val c = r.clamp01(-3.0)
    checkApprox("clamp01 idempotent", r.clamp01(c), c)
}

private fun testMissingArgument() {
    val r = CoordinateResolver(100.0, 100.0)
    checkThrows("null x throws MissingArgumentError",
        { r.resolveX(null) },
        { it is MissingArgumentError && it.argName == "x" })
    checkThrows("null y throws MissingArgumentError",
        { r.resolveY(null) },
        { it is MissingArgumentError && it.argName == "y" })
    checkThrows("null pair throws",
        { r.resolve(null, 0.5) },
        { it is MissingArgumentError })
}

private fun testZeroLengthSwipe() {
    val b = GestureBuilders()
    checkThrows("zero-length swipe throws",
        { b.swipe(0.5, 0.5, 0.5, 0.5, speed = 1000.0) },
        { it is PlanValidationError })
}

private fun testInvalidSpeed() {
    checkThrows("speed = 0 throws InvalidSpeedError",
        { DurationCalculator(0.0) },
        { it is InvalidSpeedError })
    checkThrows("speed < 0 throws InvalidSpeedError",
        { DurationCalculator(-1.0) },
        { it is InvalidSpeedError })
    checkThrows("swipe with zero speed throws",
        { GestureBuilders().swipe(0.0, 0.0, 1.0, 0.0, speed = 0.0) },
        { it is InvalidSpeedError })
}

private fun testTapDuration() {
    val timing = TimingConstants.Defaults()
    val b = GestureBuilders(timing)
    val plan = b.tap(0.3, 0.4)
    check("tap single stroke", plan.strokes.size == 1)
    val stroke = plan.strokes[0]
    check("tap 2 points", stroke.points.size == 2)
    val expectedHeld = timing.tapTimeoutMs / 2
    check("tap held = tapTimeout/2", stroke.durationMs == expectedHeld)
    check("tap duration equals held", plan.durationMs == expectedHeld)

    // Custom timing honored.
    val custom = TimingConstants.Defaults(tapTimeoutMs = 80L)
    val plan2 = GestureBuilders(custom).tap(0.1, 0.1)
    check("custom tap held = 40", plan2.durationMs == 40L)
}

private fun testSwipeDurationMath() {
    // 3-4-5 triangle in normalized space: length 0.5.
    val speed = 2.0
    val density = 1.0
    val b = GestureBuilders()
    val plan = b.swipe(0.0, 0.0, 0.3, 0.4, speed = speed, density = density)
    // len = 0.5; duration = 0.5 / (2.0 * 1.0) * 1000 = 250 ms.
    check("swipe duration == 250ms", plan.durationMs == 250L)

    // density doubles => duration halves.
    val plan2 = b.swipe(0.0, 0.0, 0.3, 0.4, speed = speed, density = 2.0)
    check("swipe density*2 halves duration", plan2.durationMs == 125L)

    // Unit length, speed 1, density 1 -> 1000ms.
    val plan3 = b.swipe(0.0, 0.0, 1.0, 0.0, speed = 1.0)
    check("unit swipe 1000ms", plan3.durationMs == 1000L)

    // Duration calculator directly.
    val dc = DurationCalculator(speed = 4.0, density = 1.0)
    check("duration calc 2.0 len @ 4 speed = 500ms", dc.durationMs(2.0) == 500L)
}

private fun testPinchSymmetry() {
    val b = GestureBuilders()
    val cx = 0.5
    val cy = 0.5
    val radius = 0.2
    val plan = b.pinchIn(cx, cy, radius, speed = 1.0, density = 1.0)
    check("pinchIn 2 strokes", plan.strokes.size == 2)
    val left = plan.strokes[0]
    val right = plan.strokes[1]
    check("pinch strokes symmetric size", left.points.size == right.points.size)
    for (i in left.points.indices) {
        val lp = left.points[i]
        val rp = right.points[i]
        // Symmetric about cx: lp.x + rp.x == 2*cx
        checkApprox("pinch symmetry x @$i", lp.x + rp.x, 2 * cx, 1e-9)
        checkApprox("pinch symmetry y @$i", lp.y, cy)
        checkApprox("pinch symmetry y @$i (right)", rp.y, cy)
        check("pinch time sync @$i", lp.t == rp.t)
    }
    // Pinch-in moves inward toward center.
    check("left start further than left end", abs(left.start.x - cx) > abs(left.end.x - cx) - 1e-12)
    check("right start further than right end", abs(right.start.x - cx) > abs(right.end.x - cx) - 1e-12)

    val out = b.pinchOut(cx, cy, radius, speed = 1.0, density = 1.0)
    val lout = out.strokes[0]
    val rout = out.strokes[1]
    checkApprox("pinchOut symmetry", lout.start.x + rout.start.x, 2 * cx)
    check("pinchOut moves outward left", abs(lout.end.x - cx) > abs(lout.start.x - cx))
    check("pinchOut moves outward right", abs(rout.end.x - cx) > abs(rout.start.x - cx))
}

private fun testSamplerMidpoint() {
    // Straight horizontal line from (0,0) to (1,0), length 1.
    val line = listOf(
        SampledPoint(0.0, 0.0, 0),
        SampledPoint(1.0, 0.0, 1),
    )
    val mid = PathSampler.pointAtArcLength(line, 0.5)
    checkApprox("sampler mid x", mid.x, 0.5)
    checkApprox("sampler mid y", mid.y, 0.0)
    checkApprox("sampler mid arc", mid.arcLength, 0.5)

    // Multi-segment L-shape: (0,0)->(1,0)->(1,1). Total length 2.
    val l = listOf(
        SampledPoint(0.0, 0.0, 0),
        SampledPoint(1.0, 0.0, 1),
        SampledPoint(1.0, 1.0, 2),
    )
    checkApprox("L total len", PathSampler.totalLength(l), 2.0)
    val corner = PathSampler.pointAtArcLength(l, 1.0)
    checkApprox("corner x", corner.x, 1.0)
    checkApprox("corner y", corner.y, 0.0)
    val halfSecond = PathSampler.pointAtArcLength(l, 1.5)
    checkApprox("half second x", halfSecond.x, 1.0)
    checkApprox("half second y", halfSecond.y, 0.5)

    // Even sampling produces count points.
    val samples = PathSampler.sampleEvenly(l, 5)
    check("even samples count", samples.size == 5)
    checkApprox("even sample 0 arc", samples[0].arcLength, 0.0)
    checkApprox("even sample last arc", samples[4].arcLength, 2.0)
    checkApprox("even sample midpoint arc", samples[2].arcLength, 1.0)
    checkApprox("even sample midpoint x", samples[2].x, 1.0)
    checkApprox("even sample midpoint y", samples[2].y, 0.0)

    // Clamping beyond ends.
    val beyond = PathSampler.pointAtArcLength(l, 100.0)
    checkApprox("clamp beyond end x", beyond.x, 1.0)
    checkApprox("clamp beyond end y", beyond.y, 1.0)
    val negative = PathSampler.pointAtArcLength(l, -5.0)
    checkApprox("clamp below start x", negative.x, 0.0)
    checkApprox("clamp below start y", negative.y, 0.0)
}

private fun testDegeneratePinch() {
    val b = GestureBuilders()
    // Zero radius -> fingers start at center; travel is zero; still valid plan.
    val plan = b.pinchIn(0.5, 0.5, radius = 0.0, speed = 1.0)
    check("degenerate pinch 2 strokes", plan.strokes.size == 2)
    check("degenerate pinch left start == left end x",
        abs(plan.strokes[0].start.x - plan.strokes[0].end.x) < 1e-12)
    check("degenerate pinch right start == right end x",
        abs(plan.strokes[1].start.x - plan.strokes[1].end.x) < 1e-12)
    check("degenerate pinch duration > 0", plan.durationMs > 0L)

    // Symmetric still holds.
    val lx = plan.strokes[0].start.x
    val rx = plan.strokes[1].start.x
    checkApprox("degenerate pinch symmetry", lx + rx, 1.0)
}

private fun testPlanValidation() {
    // Empty plan invalid.
    checkThrows("empty plan throws",
        { GesturePlan(emptyList()) },
        { it is IllegalArgumentException })

    // 11 strokes invalid.
    val single = Stroke(listOf(SampledPoint(0.5, 0.5, 0), SampledPoint(0.5, 0.5, 10)))
    checkThrows("11 strokes throws",
        { GesturePlan(List(11) { single }) },
        { it is IllegalArgumentException })

    // Non-monotonic stroke invalid.
    checkThrows("non-monotonic stroke throws",
        {
            Stroke(listOf(
                SampledPoint(0.0, 0.0, 100),
                SampledPoint(0.5, 0.5, 50),
            ))
        },
        { it is IllegalArgumentException })

    // Duration out of range.
    checkThrows("duration > 60000 throws",
        { GesturePlan(listOf(Stroke(listOf(
            SampledPoint(0.0, 0.0, 0),
            SampledPoint(0.1, 0.1, 60001),
        )))) },
        { it is IllegalArgumentException })

    // Valid plan OK.
    val ok = GesturePlan(listOf(
        Stroke(listOf(SampledPoint(0.0, 0.0, 0), SampledPoint(1.0, 1.0, 500))),
    ))
    check("valid plan duration", ok.durationMs == 500L)
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

fun main() {
    testClampBoundaries()
    testMissingArgument()
    testZeroLengthSwipe()
    testInvalidSpeed()
    testTapDuration()
    testSwipeDurationMath()
    testPinchSymmetry()
    testSamplerMidpoint()
    testDegeneratePinch()
    testPlanValidation()

    println("M1 tests: $testsPassed/$testsRun passed, $testsFailed failed")
    if (testsFailed == 0) {
        println("ALL M1 TESTS PASSED")
    } else {
        System.err.println("M1 TEST FAILURES DETECTED")
        kotlin.system.exitProcess(1)
    }
}
