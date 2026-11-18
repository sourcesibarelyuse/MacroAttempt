// M6: DSL front-end (DSL -> IR) of a macro engine.
// Pure Kotlin, no Android, no external deps.
package com.macroengine.dsl

// ---------- IR model (immutable) ----------

// A source position.
data class Span(val line: Int, val col: Int)

// The instruction set of the IR.
sealed class Op {
    data class Call(val ref: String, val span: Span) : Op()
    data class Loop(
        val label: String?,
        val everyMs: Long,
        val maxIter: Int?,
        val body: List<Op>,
        val span: Span
    ) : Op()

    data class If(
        val cond: String,
        val thenBody: List<Op>,
        val elseBody: List<Op>,
        val span: Span
    ) : Op()

    data class Action(
        val call: String,
        val args: List<String>,
        val timeoutMs: Long?,
        val onTimeout: List<Op>,
        val onFail: List<Op>,
        val span: Span
    ) : Op()

    data class Cond(val expr: String, val span: Span) : Op()
    data class Break(val span: Span) : Op()
    data class Continue(val span: Span) : Op()
    data class Return(val span: Span) : Op()
    data class Log(val msg: String, val span: Span) : Op()
}

// A target declaration: NAME: kind("value") [@conf 0.85]
data class TargetDecl(
    val name: String,
    val kind: String,
    val value: String,
    val confidence: Double?,
    val span: Span
)

// Flow metadata.
data class Meta(
    val name: String,
    val timeoutMs: Long,
    val requireCaps: List<String>
)

// The compiled flow.
data class FlowIr(
    val irVersion: Int,
    val flowId: String,
    val meta: Meta,
    val targets: List<TargetDecl>,
    val body: List<Op>,
    val subs: Map<String, List<Op>>
)

// ---------- Errors ----------

// Raised on any DSL syntax problem, carrying the offending span.
class DslSyntaxError(message: String, val span: Span) :
    RuntimeException("${message} at L${span.line}:${span.col}")

// ---------- Parser ----------

// Line-oriented recursive-descent parser producing FlowIr.
class Parser(private val source: String, private val flowId: String) {

    // A single logical line with its indentation-derived depth.
    private data class Line(
        val lineNo: Int,
        val indent: Int,
        val depth: Int,
        val text: String
    )

    private val lines: List<Line> = parseLines(source)

    // Parse the raw source into structured lines; validate indentation.
    private fun parseLines(source: String): List<Line> {
        val raw = source.split("\n")
        val out = ArrayList<Line>()
        var expectedIndent = 0
        for ((idx, original) in raw.withIndex()) {
            val lineNo = idx + 1
            // Preserve leading spaces for column computation but strip trailing.
            val noTrail = original.trimEnd()
            val trimmed = noTrail.trimStart()
            if (trimmed.isEmpty()) continue // skip blank lines
            // Tabs are not allowed; indentation must be spaces.
            if (noTrail.startsWith("\t")) {
                throw DslSyntaxError("inconsistent indentation (tab)", Span(lineNo, 1))
            }
            val indent = noTrail.length - trimmed.length
            if (indent % 2 != 0) {
                throw DslSyntaxError("inconsistent indentation (odd spaces)", Span(lineNo, 1))
            }
            val depth = indent / 2
            out.add(Line(lineNo, indent, depth, trimmed))
        }
        return out
    }

    // Cursor state for recursive descent.
    private var pos: Int = 0

    // Public entry: parse whole source.
    fun parse(): FlowIr {
        var meta: Meta? = null
        var metaSpan = Span(1, 1)
        val targets = ArrayList<TargetDecl>()
        var startBody: List<Op> = emptyList()
        val subs = LinkedHashMap<String, List<Op>>()

        pos = 0
        while (pos < lines.size) {
            val ln = lines[pos]
            if (ln.depth != 0) {
                throw DslSyntaxError("inconsistent indentation (unexpected indent)", Span(ln.lineNo, 1))
            }
            val t = ln.text
            when {
                t == "@meta {" -> {
                    metaSpan = Span(ln.lineNo, 1)
                    pos++
                    meta = parseMetaBlock()
                }
                t == "@targets {" -> {
                    pos++
                    parseTargetsBlock(targets)
                }
                t == "on start:" -> {
                    pos++
                    startBody = parseBlock(ln.depth)
                }
                t.startsWith("sub ") && t.endsWith(":") -> {
                    val name = t.substring(4, t.length - 1).trim()
                    if (name.isEmpty()) {
                        throw DslSyntaxError("missing sub name", Span(ln.lineNo, 1))
                    }
                    pos++
                    subs[name] = parseBlock(ln.depth)
                }
                else -> {
                    throw DslSyntaxError("unknown keyword", Span(ln.lineNo, 1))
                }
            }
        }

        val finalMeta = meta ?: Meta(name = flowId, timeoutMs = 0L, requireCaps = emptyList())
        return FlowIr(
            irVersion = 1,
            flowId = flowId,
            meta = finalMeta,
            targets = targets,
            body = startBody,
            subs = subs
        )
    }

    // Parse the body of '@meta {' ... '}' into a Meta.
    private fun parseMetaBlock(): Meta {
        var name = ""
        var timeoutMs = 0L
        val caps = ArrayList<String>()
        while (pos < lines.size) {
            val ln = lines[pos]
            val t = ln.text
            if (t == "}") { pos++; break }
            if (ln.depth != 1) {
                throw DslSyntaxError("inconsistent indentation in @meta", Span(ln.lineNo, 1))
            }
            val colon = t.indexOf(':')
            if (colon <= 0) {
                throw DslSyntaxError("missing ':' in @meta entry", Span(ln.lineNo, 1))
            }
            val key = t.substring(0, colon).trim()
            val value = t.substring(colon + 1).trim()
            when (key) {
                "name" -> name = value
                "timeout" -> timeoutMs = parseDuration(value, Span(ln.lineNo, 1))
                "require_caps" -> {
                    // Expect bracket list: [a, b, c]
                    if (!value.startsWith("[") || !value.endsWith("]")) {
                        throw DslSyntaxError("require_caps expects [..] list", Span(ln.lineNo, 1))
                    }
                    val inner = value.substring(1, value.length - 1).trim()
                    if (inner.isNotEmpty()) {
                        for (c in inner.split(",")) {
                            val cs = c.trim()
                            if (cs.isNotEmpty()) caps.add(cs)
                        }
                    }
                }
                else -> throw DslSyntaxError("unknown @meta key '$key'", Span(ln.lineNo, 1))
            }
            pos++
        }
        return Meta(name = name, timeoutMs = timeoutMs, requireCaps = caps)
    }

    // Parse the body of '@targets {' ... '}' appending TargetDecls.
    private fun parseTargetsBlock(out: MutableList<TargetDecl>) {
        while (pos < lines.size) {
            val ln = lines[pos]
            val t = ln.text
            if (t == "}") { pos++; break }
            if (ln.depth != 1) {
                throw DslSyntaxError("inconsistent indentation in @targets", Span(ln.lineNo, 1))
            }
            val span = Span(ln.lineNo, 1)
            val colon = t.indexOf(':')
            if (colon <= 0) {
                throw DslSyntaxError("missing ':' in target decl", span)
            }
            val name = t.substring(0, colon).trim()
            var rest = t.substring(colon + 1).trim()
            // Optional trailing @conf 0.85
            var conf: Double? = null
            val confIdx = rest.lastIndexOf("@conf")
            if (confIdx >= 0) {
                val confPart = rest.substring(confIdx + 5).trim()
                val confStr = confPart.substringBefore(' ').trim()
                conf = confStr.toDoubleOrNull()
                    ?: throw DslSyntaxError("bad @conf value", span)
                rest = rest.substring(0, confIdx).trim()
            }
            // rest should be kind("value")
            val kOpen = rest.indexOf('(')
            val kClose = rest.lastIndexOf(')')
            if (kOpen <= 0 || kClose <= kOpen) {
                throw DslSyntaxError("target expects kind(\"value\")", span)
            }
            val kind = rest.substring(0, kOpen).trim()
            var inner = rest.substring(kOpen + 1, kClose).trim()
            // Strip surrounding quotes if present.
            if (inner.length >= 2 && inner.startsWith("\"") && inner.endsWith("\"")) {
                inner = inner.substring(1, inner.length - 1)
            }
            out.add(TargetDecl(name, kind, inner, conf, span))
            pos++
        }
    }

    // Parse a block of statements at the given parent depth (children at depth+1).
    private fun parseBlock(parentDepth: Int): List<Op> {
        val out = ArrayList<Op>()
        val childDepth = parentDepth + 1
        while (pos < lines.size) {
            val ln = lines[pos]
            if (ln.depth < childDepth) break // dedent ends block
            if (ln.depth > childDepth) {
                throw DslSyntaxError("inconsistent indentation", Span(ln.lineNo, 1))
            }
            out.add(parseStatement(ln))
        }
        return out
    }

    // Parse exactly one statement (advancing pos), possibly consuming nested blocks.
    private fun parseStatement(ln: Line): Op {
        val span = Span(ln.lineNo, 1)
        val t = ln.text
        return when {
            t == "break" -> { pos++; Op.Break(span) }
            t == "continue" -> { pos++; Op.Continue(span) }
            t == "return" -> { pos++; Op.Return(span) }
            t.startsWith("log(") && t.endsWith(")") -> {
                var inner = t.substring(4, t.length - 1).trim()
                if (inner.length >= 2 && inner.startsWith("\"") && inner.endsWith("\"")) {
                    inner = inner.substring(1, inner.length - 1)
                }
                pos++
                Op.Log(inner, span)
            }
            t.startsWith("call ") -> {
                val ref = t.substring(5).trim()
                if (ref.isEmpty()) throw DslSyntaxError("call expects a ref", span)
                pos++
                Op.Call(ref, span)
            }
            t.startsWith("until ") -> {
                val expr = t.substring(6).trim()
                if (expr.isEmpty()) throw DslSyntaxError("until expects an expr", span)
                pos++
                // Sugar: until EXPR -> If(EXPR, [Break], [])
                Op.If(expr, listOf(Op.Break(span)), emptyList(), span)
            }
            t.startsWith("loop") -> parseLoop(ln, span)
            t.startsWith("if ") -> parseIf(ln, span)
            t.startsWith("await ") -> parseAwait(ln, span)
            else -> throw DslSyntaxError("unknown keyword", span)
        }
    }

    // Parse a loop statement and its nested block.
    private fun parseLoop(ln: Line, span: Span): Op {
        val t = ln.text
        if (!t.endsWith(":")) {
            throw DslSyntaxError("missing ':' after loop", span)
        }
        var header = t.substring(0, t.length - 1).trim() // drop ':'
        header = header.substring("loop".length).trim()
        var label: String? = null
        // Optional quoted label first.
        if (header.startsWith("\"")) {
            val endQ = header.indexOf('"', 1)
            if (endQ < 0) throw DslSyntaxError("unterminated loop label", span)
            label = header.substring(1, endQ)
            header = header.substring(endQ + 1).trim()
            if (header.startsWith(",")) header = header.substring(1).trim()
        }
        var everyMs = 0L
        var maxIter: Int? = null
        // Parse comma-separated constraints: every Dms, max N
        if (header.isNotEmpty()) {
            val toks = header.split(',', ' ').filter { it.isNotEmpty() }
            var i = 0
            while (i < toks.size) {
                when (toks[i]) {
                    "every" -> {
                        if (i + 1 >= toks.size) throw DslSyntaxError("missing duration after every", span)
                        everyMs = parseDuration(toks[i + 1], span)
                        i += 2
                    }
                    "max" -> {
                        if (i + 1 >= toks.size) throw DslSyntaxError("missing value after max", span)
                        maxIter = toks[i + 1].toIntOrNull()
                            ?: throw DslSyntaxError("bad max value", span)
                        i += 2
                    }
                    else -> throw DslSyntaxError("unknown loop clause " + toks[i], span)
                }
            }
        }
        pos++
        val body = parseBlock(ln.depth)
        return Op.Loop(label, everyMs, maxIter, body, span)
    }

    // Parse an if/else block.
    private fun parseIf(ln: Line, span: Span): Op {
        val t = ln.text
        if (!t.endsWith(":")) {
            throw DslSyntaxError("missing ':' after if", span)
        }
        val cond = t.substring(3, t.length - 1).trim()
        if (cond.isEmpty()) throw DslSyntaxError("if expects a condition", span)
        pos++
        val thenBody = parseBlock(ln.depth)
        var elseBody: List<Op> = emptyList()
        // Peek for matching else: at same depth.
        if (pos < lines.size && lines[pos].depth == ln.depth && lines[pos].text == "else:") {
            pos++
            elseBody = parseBlock(ln.depth)
        }
        return Op.If(cond, thenBody, elseBody, span)
    }

    // Parse an await/action statement with optional timeout and nested handlers.
    private fun parseAwait(ln: Line, span: Span): Op {
        val t = ln.text
        // Header text without leading "await ".
        var header = t.substring("await ".length).trim()
        // Extract timeout FIRST using regex; capture only the duration group.
        var timeoutMs: Long? = null
        val toMatch = Regex("timeout=([0-9]+(?:ms|s|m))").find(header)
        if (toMatch != null) {
            timeoutMs = parseDuration(toMatch.groupValues[1], span)
            // Remove ", timeout=..." from the call but keep the closing ')'.
            header = header.replace(Regex(",\\s*timeout=[0-9]+(?:ms|s|m)"), "")
            header = header.trim()
        }
        // Now header should be CALL(args)
        val open = header.indexOf('(')
        val close = header.lastIndexOf(')')
        if (open <= 0 || close <= open) {
            throw DslSyntaxError("await expects CALL(args)", span)
        }
        val callName = header.substring(0, open).trim()
        val argStr = header.substring(open + 1, close).trim()
        val args = splitArgs(argStr)
        pos++
        // Parse optional nested handler blocks at depth+1.
        var onTimeout: List<Op> = emptyList()
        var onFail: List<Op> = emptyList()
        val childDepth = ln.depth + 1
        var sawTimeout = false
        var sawFail = false
        while (pos < lines.size && lines[pos].depth == childDepth) {
            val peek = lines[pos].text
            if (peek == "else on_timeout:") {
                if (sawTimeout) throw DslSyntaxError("duplicate on_timeout", Span(lines[pos].lineNo, 1))
                sawTimeout = true
                val handlerLine = lines[pos]
                pos++
                onTimeout = parseBlock(handlerLine.depth)
            } else if (peek == "else on_fail:") {
                if (sawFail) throw DslSyntaxError("duplicate on_fail", Span(lines[pos].lineNo, 1))
                sawFail = true
                val handlerLine = lines[pos]
                pos++
                onFail = parseBlock(handlerLine.depth)
            } else {
                break
            }
        }
        // If there is a deeper line still pending that isn't a handler, bail.
        if (pos < lines.size && lines[pos].depth > ln.depth) {
            // unexpected nested content directly under await; treat as syntax error
            // unless it was consumed above.
            if (!(sawTimeout || sawFail)) {
                val bad = lines[pos]
                throw DslSyntaxError("unexpected indent under await", Span(bad.lineNo, 1))
            }
        }
        return Op.Action(callName, args, timeoutMs, onTimeout, onFail, span)
    }

    // Split top-level args of a call, honoring nested parentheses.
    private fun splitArgs(s: String): List<String> {
        if (s.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var depth = 0
        var start = 0
        var inQuote = false
        for (i in s.indices) {
            val c = s[i]
            if (c == '"') inQuote = !inQuote
            if (!inQuote) {
                when (c) {
                    '(' -> depth++
                    ')' -> depth--
                    ',' -> if (depth == 0) {
                        out.add(s.substring(start, i).trim())
                        start = i + 1
                    }
                }
            }
        }
        out.add(s.substring(start).trim())
        return out.filter { it.isNotEmpty() }
    }

    // Parse a duration value with ms/s/m suffix.
    private fun parseDuration(text: String, span: Span): Long {
        val s = text.trim()
        return when {
            s.endsWith("ms") -> s.dropLast(2).toLongOrNull()
                ?: throw DslSyntaxError("bad duration '$text'", span)
            s.endsWith("s") -> {
                val v = s.dropLast(1).toLongOrNull()
                    ?: throw DslSyntaxError("bad duration '$text'", span)
                v * 1000L
            }
            s.endsWith("m") -> {
                val v = s.dropLast(1).toLongOrNull()
                    ?: throw DslSyntaxError("bad duration '$text'", span)
                v * 60_000L
            }
            else -> s.toLongOrNull()
                ?: throw DslSyntaxError("bad duration '$text'", span)
        }
    }
}

// ---------- IR -> JSON (hand-rolled) ----------

// Escape a string for JSON.
private fun jsonEsc(s: String): String {
    val sb = StringBuilder()
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

// Serialize a Span as "L<line>:<col>".
private fun spanJson(sp: Span): String = "\"L${sp.line}:${sp.col}\""

// Serialize a list of ops.
private fun opsJson(ops: List<Op>): String {
    val sb = StringBuilder()
    sb.append('[')
    for ((i, op) in ops.withIndex()) {
        if (i > 0) sb.append(',')
        sb.append(opJson(op))
    }
    sb.append(']')
    return sb.toString()
}

// Serialize a single op.
private fun opJson(op: Op): String {
    val sb = StringBuilder()
    sb.append('{')
    when (op) {
        is Op.Call -> {
            sb.append("\"op\":\"call\",")
            sb.append("\"ref\":\"").append(jsonEsc(op.ref)).append("\",")
            sb.append("\"span\":").append(spanJson(op.span))
        }
        is Op.Loop -> {
            sb.append("\"op\":\"loop\",")
            sb.append("\"every_ms\":").append(op.everyMs).append(',')
            sb.append("\"max_iter\":").append(op.maxIter ?: "null").append(',')
            sb.append("\"label\":").append(if (op.label == null) "null" else "\"" + jsonEsc(op.label!!) + "\"").append(',')
            sb.append("\"body\":").append(opsJson(op.body)).append(',')
            sb.append("\"span\":").append(spanJson(op.span))
        }
        is Op.If -> {
            sb.append("\"op\":\"if\",")
            sb.append("\"cond\":\"").append(jsonEsc(op.cond)).append("\",")
            sb.append("\"then\":").append(opsJson(op.thenBody)).append(',')
            sb.append("\"else\":").append(opsJson(op.elseBody)).append(',')
            sb.append("\"span\":").append(spanJson(op.span))
        }
        is Op.Action -> {
            sb.append("\"op\":\"action\",")
            sb.append("\"call\":\"").append(jsonEsc(op.call)).append("\",")
            sb.append("\"args\":[")
            for ((i, a) in op.args.withIndex()) {
                if (i > 0) sb.append(',')
                sb.append('"').append(jsonEsc(a)).append('"')
            }
            sb.append("],")
            sb.append("\"timeout_ms\":").append(op.timeoutMs ?: "null").append(',')
            sb.append("\"on_timeout\":").append(opsJson(op.onTimeout)).append(',')
            sb.append("\"on_fail\":").append(opsJson(op.onFail)).append(',')
            sb.append("\"span\":").append(spanJson(op.span))
        }
        is Op.Cond -> {
            sb.append("\"op\":\"cond\",")
            sb.append("\"expr\":\"").append(jsonEsc(op.expr)).append("\",")
            sb.append("\"span\":").append(spanJson(op.span))
        }
        is Op.Break -> {
            sb.append("\"op\":\"break\",")
            sb.append("\"span\":").append(spanJson(op.span))
        }
        is Op.Continue -> {
            sb.append("\"op\":\"continue\",")
            sb.append("\"span\":").append(spanJson(op.span))
        }
        is Op.Return -> {
            sb.append("\"op\":\"return\",")
            sb.append("\"span\":").append(spanJson(op.span))
        }
        is Op.Log -> {
            sb.append("\"op\":\"log\",")
            sb.append("\"msg\":\"").append(jsonEsc(op.msg)).append("\",")
            sb.append("\"span\":").append(spanJson(op.span))
        }
    }
    sb.append('}')
    return sb.toString()
}

// Serialize the whole IR to JSON.
fun toJson(ir: FlowIr): String {
    val sb = StringBuilder()
    sb.append('{')
    sb.append("\"ir_version\":").append(ir.irVersion).append(',')
    sb.append("\"flow_id\":\"").append(jsonEsc(ir.flowId)).append("\",")
    // meta
    sb.append("\"meta\":{")
    sb.append("\"name\":\"").append(jsonEsc(ir.meta.name)).append("\",")
    sb.append("\"timeout_ms\":").append(ir.meta.timeoutMs).append(',')
    sb.append("\"require_caps\":[")
    for ((i, c) in ir.meta.requireCaps.withIndex()) {
        if (i > 0) sb.append(',')
        sb.append('"').append(jsonEsc(c)).append('"')
    }
    sb.append("]},")
    // targets
    sb.append("\"targets\":[")
    for ((i, t) in ir.targets.withIndex()) {
        if (i > 0) sb.append(',')
        sb.append('{')
        sb.append("\"name\":\"").append(jsonEsc(t.name)).append("\",")
        sb.append("\"kind\":\"").append(jsonEsc(t.kind)).append("\",")
        sb.append("\"value\":\"").append(jsonEsc(t.value)).append("\",")
        sb.append("\"confidence\":").append(t.confidence ?: "null").append(',')
        sb.append("\"span\":").append(spanJson(t.span))
        sb.append('}')
    }
    sb.append("],")
    // body
    sb.append("\"body\":").append(opsJson(ir.body)).append(',')
    // subs
    sb.append("\"subs\":{")
    var first = true
    for ((name, ops) in ir.subs) {
        if (!first) sb.append(',')
        first = false
        sb.append('"').append(jsonEsc(name)).append("\":").append(opsJson(ops))
    }
    sb.append('}')
    sb.append('}')
    return sb.toString()
}

// ---------- Test helpers (top-level, self-contained) ----------

// Assert a boolean condition; throw on failure.
private fun assertTrue(cond: Boolean) {
    if (!cond) throw IllegalStateException("assertTrue failed")
}

// Assert equality of two objects; throw on failure with a readable message.
private fun assertEq(expected: Any?, actual: Any?) {
    if (expected != actual) {
        throw IllegalStateException("assertEq failed: expected=$expected actual=$actual")
    }
}

// A named check that catches and reports errors.
private fun check(name: String, block: () -> Unit) {
    try {
        block()
    } catch (e: Throwable) {
        System.err.println("TEST FAILED: $name -> ${e.message}")
        throw e
    }
}

// Build the inventory-rotation sample DSL as structured lines.
private fun inventoryScript(): String {
    return listOf(
        "@meta {",
        "  name: inventory_rotation",
        "  timeout: 30s",
        "  require_caps: [tap, screenshot]",
        "}",
        "@targets {",
        "  FISH: img(\"fish.png\") @conf 0.85",
        "}",
        "on start:",
        "  call ensure_foreground",
        "  loop \"rot\" every 1200ms, max 200:",
        "    await tap(btn(\"FISH\"))",
        "    await wait_for(img(\"catch\"), timeout=8s)",
        "    until inv_full()",
        "    if inv_full():",
        "      break",
        "sub ensure_foreground:",
        "  call bring_to_front"
    ).joinToString("\n")
}

// Main: table-driven tests.
fun main() {
    // ---- Happy path parse ----
    check("parse inventory script") {
        val ir = Parser(inventoryScript(), "inv").parse()
        assertEq(1, ir.irVersion)
        assertEq("inv", ir.flowId)
        assertEq("inventory_rotation", ir.meta.name)
        assertEq(30_000L, ir.meta.timeoutMs)
        assertEq(2, ir.meta.requireCaps.size)
        assertEq("tap", ir.meta.requireCaps[0])

        // targets
        assertEq(1, ir.targets.size)
        val t = ir.targets[0]
        assertEq("FISH", t.name)
        assertEq("img", t.kind)
        assertEq("fish.png", t.value)
        assertEq(0.85, t.confidence)

        // body order: Call, Loop
        assertEq(2, ir.body.size)
        val call0 = ir.body[0]
        assertTrue(call0 is Op.Call)
        assertEq("ensure_foreground", (call0 as Op.Call).ref)
        // span line of the call should be line 9 (1-based)
        assertEq(10, call0.span.line)

        val loop = ir.body[1]
        assertTrue(loop is Op.Loop)
        loop as Op.Loop
        assertEq(1200L, loop.everyMs)
        assertEq(200, loop.maxIter)
        assertEq("rot", loop.label)
        // loop body: Action, Action, If(until), If
        assertEq(4, loop.body.size)
        assertTrue(loop.body[0] is Op.Action)
        val a0 = loop.body[0] as Op.Action
        assertEq("tap", a0.call)
        assertEq(1, a0.args.size)
        assertEq("btn(\"FISH\")", a0.args[0])
        assertEq(null, a0.timeoutMs)

        val a1 = loop.body[1] as Op.Action
        assertEq("wait_for", a1.call)
        assertEq(1, a1.args.size)
        // closing paren must be preserved
        assertEq("img(\"catch\")", a1.args[0])
        assertEq(8000L, a1.timeoutMs)

        // until compiles to If with [Break]
        val untilIf = loop.body[2] as Op.If
        assertEq("inv_full()", untilIf.cond)
        assertEq(1, untilIf.thenBody.size)
        assertTrue(untilIf.thenBody[0] is Op.Break)
        assertEq(0, untilIf.elseBody.size)

        // explicit if
        val ifOp = loop.body[3] as Op.If
        assertEq("inv_full()", ifOp.cond)
        assertTrue(ifOp.thenBody[0] is Op.Break)

        // subs
        assertTrue(ir.subs.containsKey("ensure_foreground"))
        val subOps = ir.subs["ensure_foreground"]!!
        assertEq(1, subOps.size)
        assertTrue(subOps[0] is Op.Call)

        // JSON sanity
        val json = toJson(ir)
        assertTrue(json.contains("\"ir_version\""))
        assertTrue(json.contains("\"loop\""))
        assertTrue(json.contains("\"L"))
        assertTrue(json.contains("inventory_rotation"))
    }

    // ---- Missing colon ----
    check("missing colon on loop") {
        val src = listOf(
            "on start:",
            "  loop every 100ms max 5",
            "    break"
        ).joinToString("\n")
        var thrown: DslSyntaxError? = null
        try {
            Parser(src, "x").parse()
        } catch (e: DslSyntaxError) {
            thrown = e
        }
        assertTrue(thrown != null)
        assertEq(2, thrown!!.span.line)
    }

    // ---- Bad indentation (odd spaces) ----
    check("bad indentation odd spaces") {
        val src = listOf(
            "on start:",
            "   call x"
        ).joinToString("\n")
        var thrown: DslSyntaxError? = null
        try {
            Parser(src, "x").parse()
        } catch (e: DslSyntaxError) {
            thrown = e
        }
        assertTrue(thrown != null)
        assertEq(2, thrown!!.span.line)
    }

    // ---- Unknown keyword ----
    check("unknown keyword") {
        val src = listOf(
            "on start:",
            "  frobnicate x"
        ).joinToString("\n")
        var thrown: DslSyntaxError? = null
        try {
            Parser(src, "x").parse()
        } catch (e: DslSyntaxError) {
            thrown = e
        }
        assertTrue(thrown != null)
        assertEq(2, thrown!!.span.line)
    }

    // ---- Duration parsing sanity via loop ----
    check("duration ms parsing") {
        val src = listOf(
            "on start:",
            "  loop every 2s max 3:",
            "    break"
        ).joinToString("\n")
        val ir = Parser(src, "x").parse()
        val loop = ir.body[0] as Op.Loop
        assertEq(2000L, loop.everyMs)
        assertEq(3, loop.maxIter)
    }

    println("ALL M6 TESTS PASSED")
}