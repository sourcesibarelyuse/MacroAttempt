package com.macroengine.app

enum class StepType { TAP, SWIPE, WAIT, COLOR_WAIT, LOOP }

data class Step(
    val type: StepType,
    val x: Double = 0.0,
    val y: Double = 0.0,
    val x2: Double = 0.0,
    val y2: Double = 0.0,
    val ms: Long = 0L,
    val color: Int = 0,
    val tol: Int = 0,
    val loopTimes: Int = 1,
    val label: String = ""
)

data class Script(
    val name: String,
    val steps: List<Step>,
    val loopCount: Int = 1,
    val speedMult: Double = 1.0
)

object ScriptCodec {

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c < ' ') sb.append("\\u").append(String.format("%04x", c.code))
                    else sb.append(c)
                }
            }
        }
        return sb.toString()
    }

    private fun stepJson(s: Step): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"type\":\"").append(s.type.name).append("\",")
        sb.append("\"x\":").append(s.x).append(",")
        sb.append("\"y\":").append(s.y).append(",")
        sb.append("\"x2\":").append(s.x2).append(",")
        sb.append("\"y2\":").append(s.y2).append(",")
        sb.append("\"ms\":").append(s.ms).append(",")
        sb.append("\"color\":").append(s.color).append(",")
        sb.append("\"tol\":").append(s.tol).append(",")
        sb.append("\"loopTimes\":").append(s.loopTimes).append(",")
        sb.append("\"label\":\"").append(esc(s.label)).append("\"")
        sb.append("}")
        return sb.toString()
    }

    fun toJson(s: Script): String {
        val sb = StringBuilder()
        sb.append("{")
        sb.append("\"name\":\"").append(esc(s.name)).append("\",")
        sb.append("\"loopCount\":").append(s.loopCount).append(",")
        sb.append("\"speedMult\":").append(s.speedMult).append(",")
        sb.append("\"steps\":[")
        for (i in s.steps.indices) {
            if (i > 0) sb.append(",")
            sb.append(stepJson(s.steps[i]))
        }
        sb.append("]")
        sb.append("}")
        return sb.toString()
    }

    private class P(val src: String) {
        var i = 0
        fun ws() { while (i < src.length && src[i].isWhitespace()) i++ }
        fun peek(): Char { ws(); return if (i < src.length) src[i] else '\u0000' }
        fun expect(c: Char) {
            ws()
            if (i >= src.length || src[i] != c) throw IllegalArgumentException("expected '" + c + "' at " + i)
            i++
        }
        fun str(): String {
            ws()
            expect('"')
            val sb = StringBuilder()
            while (i < src.length) {
                val c = src[i++]
                if (c == '"') return sb.toString()
                if (c == '\\') {
                    if (i >= src.length) throw IllegalArgumentException("bad escape")
                    val e = src[i++]
                    when (e) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000c')
                        'u' -> {
                            if (i + 4 > src.length) throw IllegalArgumentException("bad unicode")
                            val hex = src.substring(i, i + 4)
                            i += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> throw IllegalArgumentException("bad escape " + e)
                    }
                } else {
                    sb.append(c)
                }
            }
            throw IllegalArgumentException("unterminated string")
        }
        fun num(): Double {
            ws()
            val start = i
            while (i < src.length) {
                val c = src[i]
                if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || c.isDigit()) i++
                else break
            }
            if (start == i) throw IllegalArgumentException("expected number at " + start)
            return src.substring(start, i).toDouble()
        }
        fun bool(): Boolean {
            ws()
            if (src.startsWith("true", i)) { i += 4; return true }
            if (src.startsWith("false", i)) { i += 5; return false }
            throw IllegalArgumentException("expected bool")
        }
        fun nullTok(): Boolean {
            ws()
            if (src.startsWith("null", i)) { i += 4; return true }
            return false
        }
        fun skipValue() {
            ws()
            if (i >= src.length) throw IllegalArgumentException("eof")
            val c = src[i]
            when (c) {
                '"' -> { str() }
                '{' -> {
                    i++
                    ws()
                    if (peek() == '}') { i++; return }
                    while (true) {
                        str()
                        expect(':')
                        skipValue()
                        ws()
                        if (peek() == ',') { i++; continue }
                        expect('}')
                        return
                    }
                }
                '[' -> {
                    i++
                    ws()
                    if (peek() == ']') { i++; return }
                    while (true) {
                        skipValue()
                        ws()
                        if (peek() == ',') { i++; continue }
                        expect(']')
                        return
                    }
                }
                't', 'f' -> { bool() }
                'n' -> { if (!nullTok()) throw IllegalArgumentException("expected null") }
                else -> { num() }
            }
        }
    }

    private fun parseStep(p: P): Step {
        p.expect('{')
        var type = StepType.TAP
        var x = 0.0
        var y = 0.0
        var x2 = 0.0
        var y2 = 0.0
        var ms = 0L
        var color = 0
        var tol = 0
        var loopTimes = 1
        var label = ""
        p.ws()
        if (p.peek() == '}') { p.i++; return Step(type) }
        while (true) {
            val key = p.str()
            p.expect(':')
            when (key) {
                "type" -> type = StepType.valueOf(p.str())
                "x" -> x = p.num()
                "y" -> y = p.num()
                "x2" -> x2 = p.num()
                "y2" -> y2 = p.num()
                "ms" -> ms = p.num().toLong()
                "color" -> color = p.num().toInt()
                "tol" -> tol = p.num().toInt()
                "loopTimes" -> loopTimes = p.num().toInt()
                "label" -> label = p.str()
                else -> p.skipValue()
            }
            p.ws()
            val c = p.peek()
            if (c == ',') { p.i++; continue }
            if (c == '}') { p.i++; break }
            throw IllegalArgumentException("expected , or } at " + p.i)
        }
        return Step(type, x, y, x2, y2, ms, color, tol, loopTimes, label)
    }

    fun fromJson(s: String): Script {
        val p = P(s)
        p.expect('{')
        var name = ""
        var loopCount = 1
        var speedMult = 1.0
        val steps = mutableListOf<Step>()
        p.ws()
        if (p.peek() == '}') { p.i++; return Script(name, steps, loopCount, speedMult) }
        while (true) {
            val key = p.str()
            p.expect(':')
            when (key) {
                "name" -> name = p.str()
                "loopCount" -> loopCount = p.num().toInt()
                "speedMult" -> speedMult = p.num()
                "steps" -> {
                    p.expect('[')
                    p.ws()
                    if (p.peek() == ']') { p.i++ }
                    else {
                        while (true) {
                            steps.add(parseStep(p))
                            p.ws()
                            val c = p.peek()
                            if (c == ',') { p.i++; continue }
                            if (c == ']') { p.i++; break }
                            throw IllegalArgumentException("expected , or ] at " + p.i)
                        }
                    }
                }
                else -> p.skipValue()
            }
            p.ws()
            val c = p.peek()
            if (c == ',') { p.i++; continue }
            if (c == '}') { p.i++; break }
            throw IllegalArgumentException("expected , or } at " + p.i)
        }
        return Script(name, steps, loopCount, speedMult)
    }
}

fun validate(s: Script): List<String> {
    val errors = mutableListOf<String>()
    if (s.speedMult <= 0.0) errors.add("speedMult must be > 0")
    if (s.loopCount < 1) errors.add("loopCount must be >= 1")
    for ((idx, step) in s.steps.withIndex()) {
        val tag = "step[" + idx + "](" + step.type.name + ")"
        if (step.type == StepType.TAP || step.type == StepType.SWIPE || step.type == StepType.COLOR_WAIT) {
            if (step.x < 0.0 || step.x > 1.0) errors.add(tag + " x out of [0,1]=" + step.x)
            if (step.y < 0.0 || step.y > 1.0) errors.add(tag + " y out of [0,1]=" + step.y)
        }
        if (step.type == StepType.SWIPE) {
            if (step.x2 < 0.0 || step.x2 > 1.0) errors.add(tag + " x2 out of [0,1]=" + step.x2)
            if (step.y2 < 0.0 || step.y2 > 1.0) errors.add(tag + " y2 out of [0,1]=" + step.y2)
        }
        if (step.ms < 0L) errors.add(tag + " ms must be >= 0")
        if (step.loopTimes < 1) errors.add(tag + " loopTimes must be >= 1")
        if (step.tol < 0) errors.add(tag + " tol must be >= 0")
    }
    return errors
}

fun norm(px: Int, py: Int, w: Int, h: Int): Pair<Double, Double> {
    val nx = if (w <= 0) 0.0 else px.toDouble() / w.toDouble()
    val ny = if (h <= 0) 0.0 else py.toDouble() / h.toDouble()
    return Pair(nx.coerceIn(0.0, 1.0), ny.coerceIn(0.0, 1.0))
}

fun denorm(x: Double, y: Double, w: Int, h: Int): Pair<Int, Int> {
    val px = (x.coerceIn(0.0, 1.0) * w.toDouble()).toInt()
    val py = (y.coerceIn(0.0, 1.0) * h.toDouble()).toInt()
    return Pair(px.coerceIn(0, if (w > 0) w else 0), py.coerceIn(0, if (h > 0) h else 0))
}

fun jittered(ms: Long, seed: Long): Long {
    if (ms <= 0L) return ms
    var v = seed + ms * 0x9E3779B97F4A7C15L
    v = v xor (v ushr 33)
    v *= 0xFF51AFD7ED558CCDL
    v = v xor (v ushr 33)
    v *= 0xC4CEB9FE1A85EC53L
    v = v xor (v ushr 33)
    val range = 0.2
    val frac = ((v and 0x7FFFFFFFL).toDouble() / 0x7FFFFFFFL.toDouble()) * range - (range / 2.0)
    val out = (ms.toDouble() * (1.0 + frac)).toLong()
    return if (out < 0L) 0L else out
}
