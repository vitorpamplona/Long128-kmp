package com.vitorpamplona.long128

import com.vitorpamplona.long128.internal.*

/**
 * A 128-bit signed integer.
 *
 * ## Representation
 *
 * Stored as two [Long] values: [hi] (most-significant 64 bits, signed) and
 * [lo] (least-significant 64 bits, treated as unsigned). The numerical value is:
 *
 *     hi × 2^64 + lo.toULong()
 *
 * This two-Long representation is used on all platforms because Kotlin does not
 * yet support multi-field value classes (MFVC). On JVM, each `Int128` is a heap
 * object. For allocation-free hot loops, use [Int128Array] which stores values
 * in a flat `LongArray`.
 *
 * ## Overflow semantics
 *
 * All arithmetic operators use **wrapping** semantics, matching Kotlin's built-in
 * [Int] and [Long]. For example, `Int128.MAX_VALUE + Int128.ONE == Int128.MIN_VALUE`.
 * For checked arithmetic that throws on overflow, use [addExact], [subtractExact],
 * and [negateExact].
 *
 * ## Platform optimization
 *
 * Multiply and divide delegate to [multiply128] and [signedDivide128], which each
 * platform implements optimally:
 * - **JVM** (17+): Uses `Math.multiplyHigh` / `Math.unsignedMultiplyHigh` (JDK 18+)
 *   resolved via [java.lang.invoke.MethodHandle] to avoid Android D8 desugaring.
 * - **Native**: Uses C interop with `__int128`, compiled to `mul`/`umulh` (ARM64)
 *   or `mul`/`imul` (x86-64) by Clang.
 *
 * Addition and subtraction are pure Kotlin on all platforms. The carry detection
 * pattern `(result.toULong() < operand.toULong())` is recognized by LLVM and
 * HotSpot as a carry-flag test and compiled to `add`+`adc` / `adds`+`adc` without
 * branching.
 */
class Int128(val hi: Long, val lo: Long) : Comparable<Int128> {

    // ── Arithmetic (wrapping) ────────────────────────────────────────

    operator fun plus(other: Int128): Int128 {
        val resultLo = lo + other.lo
        // Carry detection: if the unsigned result is smaller than either operand,
        // the addition overflowed the 64-bit word and we need to carry into hi.
        // Both GCC and Clang compile this pattern to `add` + `adc` (x86-64) or
        // `adds` + `adc` (ARM64) — using the hardware carry flag, not a branch.
        val carry = if (resultLo.toULong() < lo.toULong()) 1L else 0L
        return Int128(hi + other.hi + carry, resultLo)
    }

    operator fun minus(other: Int128): Int128 {
        val resultLo = lo - other.lo
        val borrow = if (lo.toULong() < other.lo.toULong()) 1L else 0L
        return Int128(hi - other.hi - borrow, resultLo)
    }

    operator fun times(other: Int128): Int128 {
        val result = multiply128(hi, lo, other.hi, other.lo)
        return Int128(result[0], result[1])
    }

    /**
     * Truncated division (rounds toward zero), matching Kotlin's `/` on [Int]/[Long].
     * @throws ArithmeticException if [other] is zero
     * @throws ArithmeticException if this is [MIN_VALUE] and [other] is -1 (result overflows)
     */
    operator fun div(other: Int128): Int128 {
        val result = signedDivide128(hi, lo, other.hi, other.lo)
        return Int128(result[0], result[1])
    }

    /**
     * Remainder after truncated division, matching Kotlin's `%` on [Int]/[Long].
     * The result has the same sign as the dividend.
     * @throws ArithmeticException if [other] is zero
     */
    operator fun rem(other: Int128): Int128 {
        val result = signedDivide128(hi, lo, other.hi, other.lo)
        return Int128(result[2], result[3])
    }

    /** Two's complement negation. Note: `-MIN_VALUE == MIN_VALUE` (wraps). */
    operator fun unaryMinus(): Int128 {
        // ~x + 1 (invert all bits and add 1)
        val negatedLo = lo.inv() + 1L
        val carry = if (lo == 0L) 1L else 0L
        return Int128(hi.inv() + carry, negatedLo)
    }

    operator fun unaryPlus(): Int128 = this

    operator fun inc(): Int128 = this + ONE
    operator fun dec(): Int128 = this - ONE

    // ── Bitwise ──────────────────────────────────────────────────────

    infix fun and(other: Int128): Int128 = Int128(hi and other.hi, lo and other.lo)
    infix fun or(other: Int128): Int128 = Int128(hi or other.hi, lo or other.lo)
    infix fun xor(other: Int128): Int128 = Int128(hi xor other.hi, lo xor other.lo)
    fun inv(): Int128 = Int128(hi.inv(), lo.inv())

    /** Left shift. Shift amount is masked to 0..127 (`bitCount and 127`). */
    infix fun shl(bitCount: Int): Int128 {
        val n = bitCount and 127
        return when {
            n == 0 -> this
            n < 64 -> Int128((hi shl n) or (lo ushr (64 - n)), lo shl n)
            else -> Int128(lo shl (n - 64), 0L)
        }
    }

    /** Arithmetic right shift (sign-extending). Shift amount masked to 0..127. */
    infix fun shr(bitCount: Int): Int128 {
        val n = bitCount and 127
        return when {
            n == 0 -> this
            n < 64 -> Int128(hi shr n, (lo ushr n) or (hi shl (64 - n)))
            else -> Int128(hi shr 63, hi shr (n - 64)) // fills with sign bit
        }
    }

    /** Logical right shift (zero-filling). Shift amount masked to 0..127. */
    infix fun ushr(bitCount: Int): Int128 {
        val n = bitCount and 127
        return when {
            n == 0 -> this
            n < 64 -> Int128(hi ushr n, (lo ushr n) or (hi shl (64 - n)))
            else -> Int128(0L, hi ushr (n - 64))
        }
    }

    // ── Comparison ───────────────────────────────────────────────────

    /** Signed comparison. The high word is compared as signed, the low word as unsigned. */
    override operator fun compareTo(other: Int128): Int {
        val hiCmp = hi.compareTo(other.hi) // signed comparison of high word
        return if (hiCmp != 0) hiCmp else lo.toULong().compareTo(other.lo.toULong())
    }

    override fun equals(other: Any?): Boolean =
        other is Int128 && hi == other.hi && lo == other.lo

    override fun hashCode(): Int = 31 * hi.hashCode() + lo.hashCode()

    // ── Conversions ──────────────────────────────────────────────────

    fun toByte(): Byte = lo.toByte()
    fun toShort(): Short = lo.toShort()
    fun toInt(): Int = lo.toInt()
    fun toLong(): Long = lo

    fun toFloat(): Float = toDouble().toFloat()

    fun toDouble(): Double {
        // Fast paths for values that fit in a Long
        if (hi == 0L && lo >= 0) return lo.toDouble()
        if (hi == -1L && lo < 0) return lo.toDouble()
        val negative = isNegative()
        val abs = if (negative) -this else this
        val d = abs.hi.toULong().toDouble() * TWO_64_DOUBLE + abs.lo.toULong().toDouble()
        return if (negative) -d else d
    }

    /** Reinterprets the same 128 bits as an unsigned value. No bits change. */
    fun toUInt128(): UInt128 = UInt128(hi, lo)

    // ── String ───────────────────────────────────────────────────────

    override fun toString(): String = toString(10)

    fun toString(radix: Int): String {
        if (hi == 0L && lo == 0L) return "0"
        if (!isNegative()) return unsignedToString(hi, lo, radix)
        val abs = -this
        return "-${unsignedToString(abs.hi, abs.lo, radix)}"
    }

    // ── Bit utilities ────────────────────────────────────────────────

    fun countLeadingZeroBits(): Int = countLeadingZeros128(hi, lo)

    fun countTrailingZeroBits(): Int =
        if (lo != 0L) lo.countTrailingZeroBits() else 64 + hi.countTrailingZeroBits()

    fun countOneBits(): Int = hi.countOneBits() + lo.countOneBits()

    // ── Queries ──────────────────────────────────────────────────────

    fun isNegative(): Boolean = hi < 0

    // ── Companion ────────────────────────────────────────────────────

    companion object {
        val ZERO = Int128(0L, 0L)
        val ONE = Int128(0L, 1L)
        val NEGATIVE_ONE = Int128(-1L, -1L)

        /** 2^127 - 1 = `0x7FFFFFFFFFFFFFFF_FFFFFFFFFFFFFFFF` */
        val MAX_VALUE = Int128(Long.MAX_VALUE, -1L)

        /** -2^127 = `0x80000000_00000000_00000000_00000000` */
        val MIN_VALUE = Int128(Long.MIN_VALUE, 0L)

        const val SIZE_BITS: Int = 128
        const val SIZE_BYTES: Int = 16

        /** Creates an Int128 from a Long, sign-extending into the high word. */
        fun fromLong(value: Long): Int128 =
            Int128(if (value < 0) -1L else 0L, value)

        fun fromInt(value: Int): Int128 = fromLong(value.toLong())

        /**
         * Parses a decimal (or other radix) string as an Int128.
         * Accepts optional leading `+` or `-`.
         * @throws NumberFormatException if the string is not a valid integer.
         */
        fun parseString(s: String, radix: Int = 10): Int128 {
            require(s.isNotEmpty()) { "Empty string" }
            val negative = s[0] == '-'
            val start = if (s[0] == '-' || s[0] == '+') 1 else 0
            require(start < s.length) { "No digits" }
            val parts = parseUnsigned128(s.substring(start), radix)
            val result = Int128(parts[0], parts[1])
            return if (negative) -result else result
        }

        fun parseStringOrNull(s: String, radix: Int = 10): Int128? =
            try { parseString(s, radix) } catch (_: Exception) { null }
    }
}

/** 2^64 as a Double. Exact because it is a power of 2. */
private const val TWO_64_DOUBLE = 18446744073709551616.0

// ── Extension conversions ────────────────────────────────────────────

fun Int.toInt128(): Int128 = Int128.fromInt(this)
fun Long.toInt128(): Int128 = Int128.fromLong(this)

fun String.toInt128(radix: Int = 10): Int128 = Int128.parseString(this, radix)
fun String.toInt128OrNull(radix: Int = 10): Int128? = Int128.parseStringOrNull(this, radix)

// ── Checked arithmetic ──────────────────────────────────────────────

/** Addition that throws [ArithmeticException] on overflow instead of wrapping. */
fun Int128.addExact(other: Int128): Int128 {
    val result = this + other
    // Overflow occurs when both operands have the same sign but the result differs.
    if ((hi xor other.hi) >= 0 && (hi xor result.hi) < 0) {
        throw ArithmeticException("Int128 addition overflow")
    }
    return result
}

/** Subtraction that throws [ArithmeticException] on overflow instead of wrapping. */
fun Int128.subtractExact(other: Int128): Int128 {
    val result = this - other
    if ((hi xor other.hi) < 0 && (hi xor result.hi) < 0) {
        throw ArithmeticException("Int128 subtraction overflow")
    }
    return result
}

/** Negation that throws [ArithmeticException] if this is [Int128.MIN_VALUE]. */
fun Int128.negateExact(): Int128 {
    if (this == Int128.MIN_VALUE) throw ArithmeticException("Int128 negation overflow")
    return -this
}

/** Absolute value. Throws [ArithmeticException] if this is [Int128.MIN_VALUE]. */
fun Int128.abs(): Int128 {
    if (this == Int128.MIN_VALUE) throw ArithmeticException("Int128 abs overflow")
    return if (isNegative()) -this else this
}

/** Returns -1 if negative, 0 if zero, 1 if positive. */
val Int128.sign: Int
    get() = when {
        hi < 0 -> -1
        hi == 0L && lo == 0L -> 0
        else -> 1
    }

fun maxOf(a: Int128, b: Int128): Int128 = if (a >= b) a else b
fun minOf(a: Int128, b: Int128): Int128 = if (a <= b) a else b
