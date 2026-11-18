package com.macroengine.adapters

import com.macroengine.core.GesturePlan
import com.macroengine.core.Stroke
import com.macroengine.core.SampledPoint
import com.macroengine.core.PathSampler
import com.macroengine.core.ArcSample

// ---------------------------------------------------------------------------
// Event model
// ---------------------------------------------------------------------------

enum class Action { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP }

data class SyntheticEvent(
    val action: Action,
    val pointerIndex: Int,
    val pointerId: Int,
    val x: Double,
    val y: Double,
    val tMs: Long
)

interface EventBridge {
    fun inject(e: SyntheticEvent): Boolean
}

interface TickerClock {
    fun nowMs(): Long
}

// ---------------------------------------------------------------------------
// Capability check
// ---------------------------------------------------------------------------

fun checkCaps(strokes: List<Stroke>): InjectionResult? {
    if (strokes.size > 10) return InjectionResult.FAIL_REJECTED_CONSTRAINT
    var minStart = Long.MAX_VALUE
    var maxEnd = Long.MIN_VALUE
    for (s in strokes) {
        if (s.end.t < s.start.t) return InjectionResult.FAIL_REJECTED_CONSTRAINT
        if (s.start.t < minStart) minStart = s.start.t
        if (s.end.t > maxEnd) maxEnd = s.end.t
    }
    if (strokes.isNotEmpty() && (maxEnd - minStart) > 60000L) {
        return InjectionResult.FAIL_REJECTED_CONSTRAINT
    }
    return null
}

// ---------------------------------------------------------------------------
// Injector
// ---------------------------------------------------------------------------

class SyntheticEventInjector(
    private val bridge: EventBridge,
    private val clock: TickerClock
) {
    private class StrokeState(
        val stroke: Stroke,
        val pointerIndex: Int,
        val startT: Long,
        val endT: Long,
        val totalLen: Double
    ) {
        var started = false
        var ended = false
        // Reusable coordinate buffers
        var curX = 0.0
        var curY = 0.0
        var hasPos = false
    }

    private val active = ArrayList<StrokeState>()
    private var lastTick = Long.MIN_VALUE

    fun run(plan: GesturePlan): InjectionResult {
        checkCaps(plan.strokes)?.let { return it }

        // Sort strokes by start time; pointerIndex/pointerId = index in sorted list.
        val sorted = plan.strokes.sortedBy { it.start.t }
        val states = sorted.mapIndexed { idx, st ->
            StrokeState(
                stroke = st,
                pointerIndex = idx,
                startT = st.start.t,
                endT = st.end.t,
                totalLen = PathSampler.totalLength(st.points)
            )
        }.toMutableList()

        active.clear()
        lastTick = Long.MIN_VALUE
        var startCursor = 0
        var completed = false

        while (true) {
            // Hard safety bound
            val now = clock.nowMs()
            if (lastTick != Long.MIN_VALUE && now < lastTick) {
                // clamp regression
            }
            val tickEnd = if (lastTick == Long.MIN_VALUE) now else maxOf(now, lastTick)
            lastTick = tickEnd

            // ---- Build boundary event list for this tick -----------------
            val starts = ArrayList<StrokeState>()
            while (startCursor < states.size && states[startCursor].startT <= tickEnd) {
                val s = states[startCursor]
                if (!s.started) {
                    s.started = true
                    starts.add(s)
                }
                startCursor++
            }
            val ends = ArrayList<StrokeState>()
            for (s in active) {
                if (!s.ended && s.endT <= tickEnd) {
                    s.ended = true
                    ends.add(s)
                }
            }
            // ends sorted by endT, then stable by pointerIndex
            ends.sortWith(compareBy({ it.endT }, { it.pointerIndex }))
            starts.sortWith(compareBy({ it.startT }, { it.pointerIndex }))

            var boundaryOccurred = false

            // Merge chronologically: ends before starts at equal t.
            var ei = 0
            var si = 0
            var lastEmittedT = Long.MIN_VALUE

            fun emit(e: SyntheticEvent) {
                val t = if (e.tMs < lastEmittedT) lastEmittedT else e.tMs
                lastEmittedT = t
                bridge.inject(SyntheticEvent(e.action, e.pointerIndex, e.pointerId, e.x, e.y, t))
            }

            fun posAt(s: StrokeState, frac: Double): ArcSample {
                val clamped = if (frac < 0.0) 0.0 else if (frac > 1.0) 1.0 else frac
                return PathSampler.pointAtArcLength(s.stroke.points, clamped * s.totalLen)
            }

            fun sampleAt(s: StrokeState, t: Long): Pair<Double, Double> {
                val dur = (s.endT - s.startT).toDouble()
                val frac = if (dur <= 0.0) 1.0 else ((t - s.startT).toDouble() / dur)
                val a = posAt(s, frac)
                return a.x to a.y
            }

            while (ei < ends.size || si < starts.size) {
                val nextEndT = if (ei < ends.size) ends[ei].endT else Long.MAX_VALUE
                val nextStartT = if (si < starts.size) starts[si].startT else Long.MAX_VALUE
                val takeEnd = nextEndT <= nextStartT

                if (takeEnd) {
                    val s = ends[ei++]
                    val (x, y) = sampleAt(s, s.endT)
                    s.curX = x; s.curY = y; s.hasPos = true
                    val isLast = active.count { !it.ended } == 0
                    val action = if (isLast) Action.UP else Action.POINTER_UP
                    emit(SyntheticEvent(action, s.pointerIndex, s.pointerIndex, x, y, s.endT))
                    active.remove(s)
                    boundaryOccurred = true
                } else {
                    val s = starts[si++]
                    val (x, y) = sampleAt(s, s.startT)
                    s.curX = x; s.curY = y; s.hasPos = true
                    val wasEmpty = active.isEmpty()
                    active.add(s)
                    val action = if (wasEmpty) Action.DOWN else Action.POINTER_DOWN
                    emit(SyntheticEvent(action, s.pointerIndex, s.pointerIndex, x, y, s.startT))
                    boundaryOccurred = true
                }
            }

            // ---- Completion check ----------------------------------------
            val allStarted = states.all { it.started }
            val allEnded = states.all { it.ended }
            if (allStarted && allEnded && !completed) {
                completed = true
                return InjectionResult.OK
            }

            // ---- MOVE phase ----------------------------------------------
            var anyMoved = false
            // snapshots before update
            for (s in active) {
                val (x, y) = sampleAt(s, tickEnd)
                if (!s.hasPos || x != s.curX || y != s.curY) anyMoved = true
            }
            if (!boundaryOccurred && anyMoved) {
                for (s in active) {
                    val (x, y) = sampleAt(s, tickEnd)
                    s.curX = x; s.curY = y; s.hasPos = true
                    emit(SyntheticEvent(Action.MOVE, s.pointerIndex, s.pointerIndex, x, y, tickEnd))
                }
            } else {
                // update positions silently
                for (s in active) {
                    val (x, y) = sampleAt(s, tickEnd)
                    s.curX = x; s.curY = y; s.hasPos = true
                }
            }

            // If nothing can progress and the clock doesn't advance we rely on
            // the bounded clock guard in main().
        }
    }
}


// ---------------------------------------------------------------------------
// Test harness
// ---------------------------------------------------------------------------

private class SteppingClock(
    private var cur: Long,
    private val step: Long,
    private val maxReads: Int = 100000
) : TickerClock {
    private var reads = 0
    override fun nowMs(): Long {
        reads++
        if (reads > maxReads) {
            throw IllegalStateException("STEPPING CLOCK EXCEEDED MAX READS ($maxReads) — potential hang in injector")
        }
        val v = cur
        cur += step
        return v
    }
}

private class RecordingBridge : EventBridge {
    val events = ArrayList<SyntheticEvent>()
    override fun inject(e: SyntheticEvent): Boolean {
        events.add(e)
        return true
    }
}

private fun stroke(vararg pts: Triple<Double, Double, Long>): Stroke =
    Stroke(pts.map { SampledPoint(it.first, it.second, it.third) })

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean) {
    if (cond) {
        passed++
    } else {
        failed++
        println("FAIL: $name")
    }
}

fun main() {
    // ---- Test 1: tap hold duration --------------------------------------
    run {
        val s = stroke(Triple(10.0, 10.0, 0L), Triple(10.0, 10.0, 200L))
        val plan = GesturePlan(listOf(s))
        val bridge = RecordingBridge()
        val clock = SteppingClock(0L, 50L)
        val res = SyntheticEventInjector(bridge, clock).run(plan)
        check("tap: result OK", res == InjectionResult.OK)
        val downs = bridge.events.count { it.action == Action.DOWN }
        val ups = bridge.events.count { it.action == Action.UP }
        check("tap: one DOWN", downs == 1)
        check("tap: one UP", ups == 1)
        val first = bridge.events.first()
        val last = bridge.events.last()
        check("tap: DOWN at t=0", first.action == Action.DOWN && first.tMs == 0L)
        check("tap: UP at t=200", last.action == Action.UP && last.tMs == 200L)
    }

    // ---- Test 2: pinch ordering & counts --------------------------------
    run {
        val a = stroke(Triple(0.0, 0.0, 0L), Triple(0.0, 0.0, 100L))
        val b = stroke(Triple(100.0, 100.0, 50L), Triple(100.0, 100.0, 150L))
        val plan = GesturePlan(listOf(a, b))
        val bridge = RecordingBridge()
        val clock = SteppingClock(0L, 25L)
        val res = SyntheticEventInjector(bridge, clock).run(plan)
        check("pinch: OK", res == InjectionResult.OK)
        val counts = bridge.events.groupingBy { it.action }.eachCount()
        check("pinch: DOWN=1", counts[Action.DOWN] == 1)
        check("pinch: POINTER_DOWN=1", counts[Action.POINTER_DOWN] == 1)
        check("pinch: POINTER_UP=1", counts[Action.POINTER_UP] == 1)
        check("pinch: UP=1", counts[Action.UP] == 1)
        val idxSet = bridge.events
            .filter { it.action == Action.DOWN || it.action == Action.POINTER_DOWN ||
                      it.action == Action.POINTER_UP || it.action == Action.UP }
            .map { it.pointerIndex }.toSet()
        check("pinch: pointerIndex {0,1}", idxSet == setOf(0, 1))
        // b starts @50 (sorted by start), a @0 -> pointerIndex 0 = a, 1 = b
        val downEv = bridge.events.first { it.action == Action.DOWN }
        check("pinch: DOWN is pointer 0", downEv.pointerIndex == 0 && downEv.tMs == 0L)
        val pDown = bridge.events.first { it.action == Action.POINTER_DOWN }
        check("pinch: POINTER_DOWN is pointer 1 @50", pDown.pointerIndex == 1 && pDown.tMs == 50L)
    }

    // ---- Test 3: swipe midpoint at half duration ------------------------
    run {
        val s = stroke(Triple(0.0, 0.0, 0L), Triple(100.0, 0.0, 100L))
        val plan = GesturePlan(listOf(s))
        val bridge = RecordingBridge()
        val clock = SteppingClock(0L, 50L)
        val res = SyntheticEventInjector(bridge, clock).run(plan)
        check("swipe: OK", res == InjectionResult.OK)
        // find MOVE near t=50
        val mid = bridge.events.firstOrNull { it.action == Action.MOVE && it.tMs == 50L }
        check("swipe: MOVE at t=50 exists", mid != null)
        if (mid != null) {
            check("swipe: midpoint x ~ 50", Math.abs(mid.x - 50.0) < 1e-6)
            check("swipe: midpoint y ~ 0", Math.abs(mid.y - 0.0) < 1e-6)
        }
        // monotonic timestamps
        var ok = true
        var prev = Long.MIN_VALUE
        for (e in bridge.events) {
            if (e.tMs < prev) ok = false
            prev = e.tMs
        }
        check("swipe: monotonic timestamps", ok)
    }

    // ---- Test 4: stationary stroke suppresses MOVE ----------------------
    run {
        val s = stroke(Triple(5.0, 5.0, 0L), Triple(5.0, 5.0, 300L))
        val plan = GesturePlan(listOf(s))
        val bridge = RecordingBridge()
        val clock = SteppingClock(0L, 50L)
        val res = SyntheticEventInjector(bridge, clock).run(plan)
        check("stationary: OK", res == InjectionResult.OK)
        val moves = bridge.events.count { it.action == Action.MOVE }
        check("stationary: no MOVE", moves == 0)
    }

    // ---- Test 5: checkCaps >60000ms ------------------------------------
    run {
        val s = stroke(Triple(0.0, 0.0, 0L), Triple(0.0, 0.0, 60001L))
        val r = checkCaps(listOf(s))
        check("checkCaps: >60000ms rejected", r == InjectionResult.FAIL_REJECTED_CONSTRAINT)
    }

    // ---- Test 6: checkCaps 11 strokes ----------------------------------
    run {
        val strokes = (0 until 11).map {
            stroke(Triple(it.toDouble(), 0.0, 0L), Triple(it.toDouble(), 0.0, 50L))
        }
        val r = checkCaps(strokes)
        check("checkCaps: 11 strokes rejected", r == InjectionResult.FAIL_REJECTED_CONSTRAINT)
    }

    // ---- Test 7: monotonic timestamps across pinch ---------------------
    run {
        val a = stroke(Triple(0.0, 0.0, 0L), Triple(50.0, 0.0, 120L))
        val b = stroke(Triple(10.0, 10.0, 30L), Triple(60.0, 10.0, 90L))
        val plan = GesturePlan(listOf(a, b))
        val bridge = RecordingBridge()
        val clock = SteppingClock(0L, 10L)
        SyntheticEventInjector(bridge, clock).run(plan)
        var prev = Long.MIN_VALUE
        var mono = true
        for (e in bridge.events) {
            if (e.tMs < prev) mono = false
            prev = e.tMs
        }
        check("pinch: monotonic timestamps", mono)
    }

    // ---- Test 8: exactly-once completion -------------------------------
    run {
        val s = stroke(Triple(0.0, 0.0, 0L), Triple(10.0, 0.0, 40L))
        val plan = GesturePlan(listOf(s))
        val bridge = RecordingBridge()
        val clock = SteppingClock(0L, 10L)
        val res = SyntheticEventInjector(bridge, clock).run(plan)
        check("exactly-once: returns OK", res == InjectionResult.OK)
        val ups = bridge.events.count { it.action == Action.UP }
        check("exactly-once: single UP", ups == 1)
    }

    if (failed == 0) {
        println("ALL M4 TESTS PASSED")
    } else {
        println("$failed TEST(S) FAILED, $passed passed")
    }
}
