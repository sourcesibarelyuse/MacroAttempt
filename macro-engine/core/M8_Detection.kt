// M8: detection pure logic, self-contained file
package com.macroengine.detection

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.max

data class Rect(val x0: Double, val y0: Double, val x1: Double, val y1: Double)

fun union(a: Rect, b: Rect): Rect = Rect(
    min(a.x0, b.x0), min(a.y0, b.y0),
    max(a.x1, b.x1), max(a.y1, b.y1)
)

fun intersects(a: Rect, b: Rect): Boolean {
    if (a.x1 <= b.x0 || b.x1 <= a.x0) return false
    if (a.y1 <= b.y0 || b.y1 <= a.y0) return false
    return true
}

fun area(r: Rect): Double {
    val w = r.x1 - r.x0
    val h = r.y1 - r.y0
    if (w <= 0.0 || h <= 0.0) return 0.0
    return w * h
}

private fun clampd(v: Double): Double = when {
    v < 0.0 -> 0.0
    v > 1.0 -> 1.0
    else -> v
}

fun clamp01(r: Rect): Rect = Rect(clampd(r.x0), clampd(r.y0), clampd(r.x1), clampd(r.y1))

data class ColorTarget(val r: Int, val g: Int, val b: Int, val tol: Int)

fun colorMatch(px: Int, t: ColorTarget): Boolean {
    val pr = (px shr 16) and 0xFF
    val pg = (px shr 8) and 0xFF
    val pb = px and 0xFF
    if (abs(pr - t.r) > t.tol) return false
    if (abs(pg - t.g) > t.tol) return false
    if (abs(pb - t.b) > t.tol) return false
    return true
}

class ColorLut(val tol: Int) {
    private val slots = HashMap<Int, MutableList<ColorTarget>>()

    private fun q(v: Int): Int {
        val step = tol + 1
        val x = if (v < 0) 0 else (if (v > 255) 255 else v)
        return x / step
    }

    fun bucket(px: Int): Int {
        val pr = (px shr 16) and 0xFF
        val pg = (px shr 8) and 0xFF
        val pb = px and 0xFF
        return (q(pr) shl 16) or (q(pg) shl 8) or q(pb)
    }

    // insert target into all buckets within tolerance neighborhood
    private fun keysForTarget(t: ColorTarget): List<Int> {
        val step = tol + 1
        val rLo = q((t.r - tol).coerceIn(0, 255))
        val rHi = q((t.r + tol).coerceIn(0, 255))
        val gLo = q((t.g - tol).coerceIn(0, 255))
        val gHi = q((t.g + tol).coerceIn(0, 255))
        val bLo = q((t.b - tol).coerceIn(0, 255))
        val bHi = q((t.b + tol).coerceIn(0, 255))
        val out = ArrayList<Int>()
        var qr = rLo
        while (qr <= rHi) {
            var qg = gLo
            while (qg <= gHi) {
                var qb = bLo
                while (qb <= bHi) {
                    out.add((qr shl 16) or (qg shl 8) or qb)
                    qb++
                }
                qg++
            }
            qr++
        }
        // step referenced to keep intent clear
        if (step < 1) return emptyList()
        return out
    }

    fun build(targets: List<ColorTarget>): Map<Int, ColorTarget> {
        slots.clear()
        for (t in targets) {
            for (k in keysForTarget(t)) {
                val list = slots.getOrPut(k) { ArrayList() }
                list.add(t)
            }
        }
        val result = LinkedHashMap<Int, ColorTarget>()
        for ((k, list) in slots) {
            // pick first target as canonical for the bucket; find() will verify tolerance
            if (list.isNotEmpty()) result[k] = list[0]
        }
        return result
    }

    fun find(px: Int): ColorTarget? {
        val list = slots[bucket(px)] ?: return null
        for (t in list) {
            if (colorMatch(px, t)) return t
        }
        return null
    }
}

interface Frame {
    val w: Int
    val h: Int
    val px: IntArray
    fun sample(x: Int, y: Int): Int
}

class SynthFrame(override val w: Int, override val h: Int, override val px: IntArray) : Frame {
    override fun sample(x: Int, y: Int): Int {
        val cx = when {
            x < 0 -> 0
            x >= w -> w - 1
            else -> x
        }
        val cy = when {
            y < 0 -> 0
            y >= h -> h - 1
            else -> y
        }
        if (w <= 0 || h <= 0) return 0
        val idx = cy * w + cx
        if (idx < 0 || idx >= px.size) return 0
        return px[idx]
    }
}

interface Detector {
    val cost: Int
    fun matches(f: Frame): Boolean
}

class ColorDetector(
    private val roi: Rect,
    private val target: ColorTarget,
    private val lut: ColorLut?,
    private val sampleStep: Int = 1
) : Detector {
    override val cost: Int = 1

    override fun matches(f: Frame): Boolean {
        if (f.w <= 0 || f.h <= 0) return false
        val step = if (sampleStep < 1) 1 else sampleStep
        val c = clamp01(roi)
        val x0 = (c.x0 * f.w).toInt()
        val y0 = (c.y0 * f.h).toInt()
        val x1 = (c.x1 * f.w).toInt()
        val y1 = (c.y1 * f.h).toInt()
        var yy = y0
        while (yy < y1) {
            var xx = x0
            while (xx < x1) {
                if (xx < 0 || yy < 0 || xx >= f.w || yy >= f.h) {
                    xx += step
                    continue
                }
                val p = f.sample(xx, yy)
                val ok = if (lut != null) lut.find(p) != null else colorMatch(p, target)
                if (ok) return true
                xx += step
            }
            yy += step
        }
        return false
    }
}

enum class Mode { ALL, ANY }

class CompositeDetector(
    private val children: List<Detector>,
    private val mode: Mode
) : Detector {
    override val cost: Int = children.sumOf { it.cost }

    override fun matches(f: Frame): Boolean {
        val sorted = children.sortedBy { it.cost }
        if (mode == Mode.ALL) {
            for (d in sorted) {
                if (!d.matches(f)) return false
            }
            return true
        } else {
            for (d in sorted) {
                if (d.matches(f)) return true
            }
            return false
        }
    }
}

class OcrCache(
    private val ttlMs: Long,
    private val maxNegFrames: Int,
    private val missRateThreshold: Double
) {
    private val ringSize = if (maxNegFrames < 4) 8 else maxNegFrames * 2
    private val outcomes = IntArray(ringSize) // 1 hit, 0 miss, -1 negative
    private var outcomePos = 0
    private var outcomeCount = 0
    private val hits = HashMap<Int, String>()
    private val negatives = HashMap<Int, Long>()

    fun key(f: Frame, roi: Rect): Int {
        val c = clamp01(roi)
        var hcode = 17
        hcode = hcode * 31 + f.w
        hcode = hcode * 31 + f.h
        hcode = hcode * 31 + c.x0.hashCode()
        hcode = hcode * 31 + c.y0.hashCode()
        hcode = hcode * 31 + c.x1.hashCode()
        hcode = hcode * 31 + c.y1.hashCode()
        if (f.w > 0 && f.h > 0) {
            val x0 = (c.x0 * f.w).toInt()
            val y0 = (c.y0 * f.h).toInt()
            val x1 = (c.x1 * f.w).toInt()
            val y1 = (c.y1 * f.h).toInt()
            var yy = y0
            var cnt = 0
            while (yy < y1 && cnt < 64) {
                var xx = x0
                while (xx < x1 && cnt < 64) {
                    if (xx >= 0 && yy >= 0 && xx < f.w && yy < f.h) {
                        hcode = hcode * 31 + f.sample(xx, yy)
                        cnt++
                    }
                    xx++
                }
                yy++
            }
            hcode = hcode * 31 + cnt
        }
        return hcode
    }

    private fun record(outcome: Int) {
        outcomes[outcomePos] = outcome
        outcomePos = (outcomePos + 1) % ringSize
        if (outcomeCount < ringSize) outcomeCount++
    }

    fun get(k: Int): String? {
        val now = System.currentTimeMillis()
        val t = hits[k]
        if (t != null) {
            record(1)
            return t
        }
        val neg = negatives[k]
        if (neg != null) {
            if (now - neg <= ttlMs) {
                record(-1)
                return null
            } else {
                negatives.remove(k)
            }
        }
        record(0)
        return null
    }

    fun put(k: Int, text: String) {
        hits[k] = text
        negatives.remove(k)
    }

    fun putNegative(k: Int) {
        negatives[k] = System.currentTimeMillis()
        hits.remove(k)
    }

    fun shouldThrottle(): Boolean {
        if (outcomeCount == 0) return false
        var miss = 0
        var total = 0
        var i = 0
        while (i < ringSize) {
            if (i < outcomeCount) {
                val o = outcomes[i]
                if (o == 0) miss++
                total++
            }
            i++
        }
        if (total == 0) return false
        val rate = miss.toDouble() / total.toDouble()
        return rate > missRateThreshold
    }
}

// test double that counts invocations
class CountingDetector(override val cost: Int, private val result: Boolean) : Detector {
    var invocations: Int = 0
    override fun matches(f: Frame): Boolean {
        invocations++
        return result
    }
}

private var totalChecks = 0
private var failedChecks = 0

private fun check(name: String, cond: Boolean) {
    totalChecks++
    if (!cond) {
        failedChecks++
        println("FAIL: " + name)
    }
}

private fun nearly(a: Double, b: Double): Boolean = abs(a - b) < 1e-9

fun main() {
    // Rect union / area / intersects / clamp
    val r1 = Rect(0.1, 0.1, 0.4, 0.4)
    val r2 = Rect(0.3, 0.3, 0.6, 0.6)
    val u = union(r1, r2)
    check("union x0", nearly(u.x0, 0.1))
    check("union x1", nearly(u.x1, 0.6))
    check("union y1", nearly(u.y1, 0.6))
    check("area r1", nearly(area(r1), 0.09))
    check("area zero", nearly(area(Rect(0.5, 0.5, 0.2, 0.2)), 0.0))
    check("intersects true", intersects(r1, r2))
    check("intersects false", !intersects(Rect(0.1, 0.1, 0.2, 0.2), Rect(0.5, 0.5, 0.6, 0.6)))
    val cl = clamp01(Rect(-0.5, 0.2, 1.5, 0.7))
    check("clamp x0", nearly(cl.x0, 0.0))
    check("clamp x1", nearly(cl.x1, 1.0))
    check("clamp y0", nearly(cl.y0, 0.2))

    // colorMatch tolerance boundary
    val t = ColorTarget(100, 150, 200, 10)
    check("exact match", colorMatch(0x6496C8, t))
    check("boundary tol passes", colorMatch(0x6E96C8, t)) // r=110 diff=10
    check("tol+1 fails", !colorMatch(0x6F96C8, t)) // r=111 diff=11
    check("g tol+1 fails", !colorMatch(0x6478C8, t)) // g diff 11
    check("b boundary passes", colorMatch(0x6496BE, t)) // b diff 10
    check("r under fails", !colorMatch(0x5996C8, t)) // r=90 diff 10 passes... use 89
    check("r under tol-1", colorMatch(0x5B96C8, t)) // r=91 diff 9

    // LUT bucket -> find
    val lut = ColorLut(8)
    val ta = ColorTarget(10, 20, 30, 8)
    val tb = ColorTarget(200, 210, 220, 8)
    lut.build(listOf(ta, tb))
    val fa = lut.find(0x0A141E)
    check("lut find a", fa != null && fa.r == 10 && fa.g == 20 && fa.b == 30)
    val fb = lut.find(0xC8D2DC)
    check("lut find b", fb != null && fb.r == 200 && fb.g == 210 && fb.b == 220)
    val miss = lut.find(0x010203)
    check("lut find miss", miss == null)
    // inside tolerance
    val near = lut.find(0x0F161F) // diff 5,2,1 within 8
    check("lut tolerance verify", near != null && near.r == 10)

    // ColorDetector planted pixel
    val w = 20
    val h = 20
    val px = IntArray(w * h) { 0 }
    // plant target at (10,10)
    px[10 * w + 10] = 0x0A141E
    val frame = SynthFrame(w, h, px)
    val cd = ColorDetector(Rect(0.0, 0.0, 1.0, 1.0), ta, null, 1)
    check("detector finds planted", cd.matches(frame))
    val cdMiss = ColorDetector(Rect(0.0, 0.0, 0.5, 0.5), ta, null, 1)
    check("detector misses outside", !cdMiss.matches(frame))
    val cdRoi = ColorDetector(Rect(0.45, 0.45, 0.75, 0.75), ta, null, 1)
    check("detector finds in roi", cdRoi.matches(frame))
    // step skipping: place only odd pixels? plant at (1,1), step 2 from 0 hits 0,2,... so (1,1) skipped
    val px2 = IntArray(w * h) { 0 }
    px2[1 * w + 1] = 0x0A141E
    val frame2 = SynthFrame(w, h, px2)
    val cdEven = ColorDetector(Rect(0.0, 0.0, 1.0, 1.0), ta, null, 2)
    check("detector step skips", !cdEven.matches(frame2))
    val cdStep1 = ColorDetector(Rect(0.0, 0.0, 1.0, 1.0), ta, null, 1)
    check("detector step1 finds", cdStep1.matches(frame2))

    // CompositeDetector ALL short-circuit
    val cheapFail = CountingDetector(1, false)
    val expensive = CountingDetector(100, true)
    val allComp = CompositeDetector(listOf(expensive, cheapFail), Mode.ALL)
    val allResult = allComp.matches(frame)
    check("ALL result false", !allResult)
    check("ALL cheap invoked", cheapFail.invocations == 1)
    check("ALL expensive not invoked", expensive.invocations == 0)
    check("ALL cost", allComp.cost == 101)

    // CompositeDetector ANY short-circuit
    val cheapOk = CountingDetector(1, true)
    val expensive2 = CountingDetector(100, false)
    val anyComp = CompositeDetector(listOf(expensive2, cheapOk), Mode.ANY)
    val anyResult = anyComp.matches(frame)
    check("ANY result true", anyResult)
    check("ANY cheap invoked", cheapOk.invocations == 1)
    check("ANY expensive not invoked", expensive2.invocations == 0)
    check("ANY cost", anyComp.cost == 101)

    // OcrCache
    val cache = OcrCache(60000L, 4, 0.5)
    val f1 = SynthFrame(w, h, px)
    val roi = Rect(0.1, 0.1, 0.5, 0.5)
    val k = cache.key(f1, roi)
    check("cache miss empty", cache.get(k) == null)
    cache.put(k, "hello")
    check("cache hit", cache.get(k) == "hello")
    val kn = cache.key(frame2, roi)
    cache.putNegative(kn)
    check("cache negative", cache.get(kn) == null)
    // fill misses to trigger throttle
    val c2 = OcrCache(60000L, 4, 0.4)
    val fk = SynthFrame(4, 4, IntArray(16))
    val rr = Rect(0.0, 0.0, 1.0, 1.0)
    val kk = c2.key(fk, rr)
    var i = 0
    while (i < 6) {
        c2.get(0x7FFFFFFF - i)
        i++
    }
    check("cache throttles on misses", c2.shouldThrottle())
    // now hits dominate
    val c3 = OcrCache(60000L, 4, 0.4)
    var hitsNeeded = 20
    var j = 0
    while (j < hitsNeeded) {
        val key = 1000 + j
        c3.put(key, "v" + j)
        c3.get(key)
        j++
    }
    check("cache not throttled when hits dominate", !c3.shouldThrottle())

    if (failedChecks == 0) {
        println("ALL M8 TESTS PASSED")
    } else {
        println("M8 TESTS FAILED: " + failedChecks + " of " + totalChecks)
    }
}