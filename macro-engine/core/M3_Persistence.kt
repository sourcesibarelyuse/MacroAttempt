package com.macroengine.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

const val SCHEMA_VERSION = 2

class UnsupportedSchemaError(v: Int) :
    RuntimeException("Unsupported schema version: $v (max supported: $SCHEMA_VERSION)")

data class GestureStatement(
    val kind: String,
    val x: Double,
    val y: Double,
    val speed: Double,
    val displayId: Int? = null
)

/**
 * Binary codec for [GestureStatement] records.
 *
 * Wire format (fixed field order):
 *   int32   schemaVersion      (must be <= SCHEMA_VERSION on read)
 *   UTF     kind
 *   f64     x
 *   f64     y
 *   f64     speed
 *   [int32  displayId]         (present only when schemaVersion >= 2)
 *
 * A `displayId` of [NO_DISPLAY_OVERRIDE] encodes `null`.
 */
object StatementCodec {

    /** Sentinel written on the wire when [GestureStatement.displayId] is null. */
    private const val NO_DISPLAY_OVERRIDE = Int.MIN_VALUE

    fun write(out: DataOutputStream, s: GestureStatement) {
        out.writeInt(SCHEMA_VERSION)
        out.writeUTF(s.kind)
        out.writeDouble(s.x)
        out.writeDouble(s.y)
        out.writeDouble(s.speed)
        out.writeInt(s.displayId ?: NO_DISPLAY_OVERRIDE)
    }

    fun read(inp: DataInputStream): GestureStatement {
        val version = inp.readInt()
        if (version > SCHEMA_VERSION) {
            throw UnsupportedSchemaError(version)
        }
        if (version < 1) {
            throw UnsupportedSchemaError(version)
        }

        val kind = inp.readUTF()
        val x = inp.readDouble()
        val y = inp.readDouble()
        val speed = inp.readDouble()

        val displayId: Int? =
            if (version >= 2) {
                val raw = inp.readInt()
                if (raw == NO_DISPLAY_OVERRIDE) null else raw
            } else {
                null
            }

        return GestureStatement(kind, x, y, speed, displayId)
    }
}

// ---------------------------------------------------------------------------
// Self-tests
// ---------------------------------------------------------------------------

private fun encode(s: GestureStatement): ByteArray {
    val bos = ByteArrayOutputStream()
    DataOutputStream(bos).use { StatementCodec.write(it, s) }
    return bos.toByteArray()
}

private fun decode(bytes: ByteArray): GestureStatement {
    return DataInputStream(ByteArrayInputStream(bytes)).use { StatementCodec.read(it) }
}

/** Hand-build a legacy v1 record: version, kind, x, y, speed (no displayId). */
private fun legacyV1Bytes(
    kind: String,
    x: Double,
    y: Double,
    speed: Double
): ByteArray {
    val bos = ByteArrayOutputStream()
    DataOutputStream(bos).use { out ->
        out.writeInt(1)
        out.writeUTF(kind)
        out.writeDouble(x)
        out.writeDouble(y)
        out.writeDouble(speed)
        // Note: no displayId field at v1
    }
    return bos.toByteArray()
}

fun main() {
    // 1. v2 round-trip deep-equal
    val outOfThinAir = GestureStatement("tap", 12.5, -3.25, 0.8, 7)
    val rt = decode(encode(outOfThinAir))
    check(rt == outOfThinAir) { "v2 round-trip mismatch: $rt != $outOfThinAir" }

    val nullDisplay = GestureStatement("swipe", 1.0, 2.0, 0.5, null)
    val rtNull = decode(encode(nullDisplay))
    check(rtNull == nullDisplay) { "v2 round-trip (null display) mismatch" }

    // 2. legacy v1 fixture — displayId must come back as null
    val v1 = decode(legacyV1Bytes("tap", 10.0, 20.0, 0.9))
    check(v1.kind == "tap")
    check(v1.x == 10.0)
    check(v1.y == 20.0)
    check(v1.speed == 0.9)
    check(v1.displayId == null) { "legacy v1 should yield null displayId, got ${v1.displayId}" }

    // 3. version-99 header rejects with UnsupportedSchemaError
    val bogus = ByteArrayOutputStream()
    DataOutputStream(bogus).use { it.writeInt(99) }
    var threw = false
    try {
        decode(bogus.toByteArray())
    } catch (e: UnsupportedSchemaError) {
        threw = true
    }
    check(threw) { "expected UnsupportedSchemaError for v99" }

    // 4. truncated stream fails cleanly (EOFException or IOException, not something else)
    val full = encode(outOfThinAir)
    val truncated = full.copyOf(full.size - 3)
    var cleanFailure = false
    try {
        decode(truncated)
    } catch (e: EOFException) {
        cleanFailure = true
    } catch (e: IOException) {
        cleanFailure = true
    }
    check(cleanFailure) { "truncated stream should throw EOFException/IOException" }

    // Additional: truncated in displayId region (missing the trailing 4 bytes only)
    val noDisplayTail = full.copyOf(full.size - 4)
    var cleanFailure2 = false
    try {
        decode(noDisplayTail)
    } catch (e: EOFException) {
        cleanFailure2 = true
    } catch (e: IOException) {
        cleanFailure2 = true
    }
    check(cleanFailure2) { "truncated displayId should throw EOFException/IOException" }

    println("ALL M3 TESTS PASSED")
}