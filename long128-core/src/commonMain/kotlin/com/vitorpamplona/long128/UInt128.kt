package com.vitorpamplona.long128

import com.vitorpamplona.long128.internal.*

/**
 * A 128-bit unsigned integer.
 *
 * ## Representation
 *
 * Stored as two [Long] values: [hi] and [lo], both treated as unsigned.
 * The numerical value is:
 *
 *     hi.toULong() × 2^64 + lo.toULong()
 *
 * This is the same bit layout as [Int128] — only the interpretation differs.
 * Converting between them via [toInt128] / [Int128.toUInt128] reinterprets
 * the bits without changing them (like `reinterpret_cast` in C++).
 *
 * ## Differences from Int128
 *
 * - **Comparison**: Unsigned total order (0 is smallest, 2^128-1 is largest).
 * - **Division/remainder**: Unsigned semantics — no sign handling.
 * - **Right shift** (`shr`): Always logical (zero-fill), never arithmetic.
 * - **No unary minus**: Unsigned values have no sign.
 * - **toString**: Never produces a leading `-`.
 *
 * All other operations (add, sub, mul, bitwise, shl) produce identical bit
 * patterns to [Int128] — wrapping arithmetic is sign-agnostic.
 */
class UInt128(val hi: Long, val lo: Long) : Comparable<UInt128> {

    // ── Arithmetic (wrapping mod 2^128) ──────────────────────────────

    operator fun plus(other: UInt128): UInt128 {
        val resultLo = lo + other.lo
        val carry = if (resultLo.toULong() < lo.toULong()) 1L else 0L
        return UInt128(hi + other.hi + carry, resultLo)
    }

    operator fun minus(other: UInt128): UInt128 {
        val resultLo = lo - other.lo
        val borrow = if (lo.toULong() < other.lo.toULong()) 1L else 0L
        return UInt128(hi - other.hi - borrow, resultLo)
    }

    operator fun times(other: UInt128): UInt128 {
        val result = multiply128(hi, lo, other.hi, other.lo)
        return UInt128(result[0], result[1])
    }

    /** Unsigned division. @throws ArithmeticException if [other] is zero. */
    operator fun div(other: UInt128): UInt128 {
        val result = unsignedDivide128(hi, lo, other.hi, other.lo)
        return UInt128(result[0], result[1])
    }

    /** Unsigned remainder. @throws ArithmeticException if [other] is zero. */
    operator fun rem(other: UInt128): UInt128 {
        val result = unsignedDivide128(hi, lo, other.hi, other.lo)
        return UInt128(result[2], result[3])
    }

    operator fun inc(): UInt128 = this + ONE
    operator fun dec(): UInt128 = this - ONE

    // ── Bitwise ──────────────────────────────────────────────────────

    infix fun and(other: UInt128): UInt128 = UInt128(hi and other.hi, lo and other.lo)
    infix fun or(other: UInt128): UInt128 = UInt128(hi or other.hi, lo or other.lo)
    infix fun xor(other: UInt128): UInt128 = UInt128(hi xor other.hi, lo xor other.lo)
    fun inv(): UInt128 = UInt128(hi.inv(), lo.inv())

    /** Left shift. Shift amount masked to 0..127. */
    infix fun shl(bitCount: Int): UInt128 {
        val n = bitCount and 127
        return when {
            n == 0 -> this
            n < 64 -> UInt128((hi shl n) or (lo ushr (64 - n)), lo shl n)
            else -> UInt128(lo shl (n - 64), 0L)
        }
    }

    /** Logical right shift (always zero-filling). Shift amount masked to 0..127. */
    infix fun shr(bitCount: Int): UInt128 {
        val n = bitCount and 127
        return when {
            n == 0 -> this
            n < 64 -> UInt128(hi ushr n, (lo ushr n) or (hi shl (64 - n)))
            else -> UInt128(0L, hi ushr (n - 64))
        }
    }

    // ── Comparison (unsigned) ────────────────────────────────────────

    /**
     * Unsigned comparison. Both [hi] and [lo] are compared as unsigned values.
     * This means `UInt128(-1L, -1L)` (all bits set = MAX_VALUE) is greater than
     * `UInt128(Long.MAX_VALUE, -1L)`.
     */
    override operator fun compareTo(other: UInt128): Int =
        compareUnsigned128(hi, lo, other.hi, other.lo)

    override fun equals(other: Any?): Boolean =
        other is UInt128 && hi == other.hi && lo == other.lo

    override fun hashCode(): Int = 31 * hi.hashCode() + lo.hashCode()

    // ── Conversions ──────────────────────────────────────────────────

    fun toByte(): Byte = lo.toByte()
    fun toShort(): Short = lo.toShort()
    fun toInt(): Int = lo.toInt()
    fun toLong(): Long = lo
    fun toUInt(): UInt = lo.toUInt()
    fun toULong(): ULong = lo.toULong()

    fun toFloat(): Float = toDouble().toFloat()

    fun toDouble(): Double {
        if (hi == 0L && lo >= 0) return lo.toDouble()
        if (hi == 0L) return lo.toULong().toDouble()
        return hi.toULong().toDouble() * TWO_64_DOUBLE + lo.toULong().toDouble()
    }

    /** Reinterprets the same 128 bits as a signed value. No bits change. */
    fun toInt128(): Int128 = Int128(hi, lo)

    // ── String ───────────────────────────────────────────────────────

    override fun toString(): String = toString(10)
    fun toString(radix: Int): String = unsignedToString(hi, lo, radix)

    // ── Bit utilities ────────────────────────────────────────────────

    fun countLeadingZeroBits(): Int = countLeadingZeros128(hi, lo)

    fun countTrailingZeroBits(): Int =
        if (lo != 0L) lo.countTrailingZeroBits() else 64 + hi.countTrailingZeroBits()

    fun countOneBits(): Int = hi.countOneBits() + lo.countOneBits()

    // ── Companion ────────────────────────────────────────────────────

    companion object {
        val ZERO = UInt128(0L, 0L)
        val ONE = UInt128(0L, 1L)

        /** 2^128 - 1 = `0xFFFFFFFFFFFFFFFF_FFFFFFFFFFFFFFFF` (all bits set). */
        val MAX_VALUE = UInt128(-1L, -1L)
        val MIN_VALUE = ZERO

        const val SIZE_BITS: Int = 128
        const val SIZE_BYTES: Int = 16

        /** Creates a UInt128 from a Long. The high word is zero (no sign extension). */
        fun fromLong(value: Long): UInt128 = UInt128(0L, value)
        fun fromULong(value: ULong): UInt128 = UInt128(0L, value.toLong())

        fun parseString(s: String, radix: Int = 10): UInt128 {
            require(s.isNotEmpty()) { "Empty string" }
            require(s[0] != '-') { "Unsigned value cannot be negative" }
            val start = if (s[0] == '+') 1 else 0
            require(start < s.length) { "No digits" }
            val parts = parseUnsigned128(s.substring(start), radix)
            return UInt128(parts[0], parts[1])
        }

        fun parseStringOrNull(s: String, radix: Int = 10): UInt128? =
            try { parseString(s, radix) } catch (_: Exception) { null }
    }
}

/** 2^64 as a Double. Exact because it is a power of 2. */
private const val TWO_64_DOUBLE = 18446744073709551616.0

// ── Extension conversions ────────────────────────────────────────────

fun UInt.toUInt128(): UInt128 = UInt128(0L, this.toLong() and 0xFFFFFFFFL)
fun ULong.toUInt128(): UInt128 = UInt128(0L, this.toLong())

fun String.toUInt128(radix: Int = 10): UInt128 = UInt128.parseString(this, radix)
fun String.toUInt128OrNull(radix: Int = 10): UInt128? = UInt128.parseStringOrNull(this, radix)

// ── Checked arithmetic ──────────────────────────────────────────────

/** Addition that throws on unsigned overflow (result wrapped below either operand). */
fun UInt128.addExact(other: UInt128): UInt128 {
    val result = this + other
    if (result < this) throw ArithmeticException("UInt128 addition overflow")
    return result
}

/** Subtraction that throws on unsigned underflow (subtrahend > minuend). */
fun UInt128.subtractExact(other: UInt128): UInt128 {
    if (this < other) throw ArithmeticException("UInt128 subtraction underflow")
    return this - other
}

fun maxOf(a: UInt128, b: UInt128): UInt128 = if (a >= b) a else b
fun minOf(a: UInt128, b: UInt128): UInt128 = if (a <= b) a else b
