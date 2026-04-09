package com.vitorpamplona.long128

import com.vitorpamplona.long128.internal.*

/**
 * A 128-bit signed integer stored as two [Long] halves.
 *
 * The numerical value is `hi * 2^64 + lo.toULong()`.
 * All arithmetic operators use **wrapping** semantics (same as Kotlin [Int]/[Long]).
 */
class Int128(val hi: Long, val lo: Long) : Comparable<Int128> {

    // -- Arithmetic (wrapping) --

    operator fun plus(other: Int128): Int128 {
        val newLo = lo + other.lo
        val carry = if (newLo.toULong() < lo.toULong()) 1L else 0L
        return Int128(hi + other.hi + carry, newLo)
    }

    operator fun minus(other: Int128): Int128 {
        val newLo = lo - other.lo
        val borrow = if (lo.toULong() < other.lo.toULong()) 1L else 0L
        return Int128(hi - other.hi - borrow, newLo)
    }

    operator fun times(other: Int128): Int128 {
        val newLo = lo * other.lo
        val newHi = platformUnsignedMultiplyHigh(lo, other.lo) + hi * other.lo + lo * other.hi
        return Int128(newHi, newLo)
    }

    operator fun div(other: Int128): Int128 {
        if (other.hi == 0L && other.lo == 0L) throw ArithmeticException("Division by zero")
        if (hi == Long.MIN_VALUE && lo == 0L && other.hi == -1L && other.lo == -1L) {
            throw ArithmeticException("Int128 overflow: MIN_VALUE / -1")
        }

        val aNeg = isNegative()
        val bNeg = other.isNegative()
        val aAbs = if (aNeg) (-this) else this
        val bAbs = if (bNeg) (-other) else other

        val r = udivrem128(aAbs.hi, aAbs.lo, bAbs.hi, bAbs.lo)
        val q = Int128(r[0], r[1])
        return if (aNeg != bNeg) -q else q
    }

    operator fun rem(other: Int128): Int128 {
        if (other.hi == 0L && other.lo == 0L) throw ArithmeticException("Division by zero")

        val aNeg = isNegative()
        val bNeg = other.isNegative()
        val aAbs = if (aNeg) (-this) else this
        val bAbs = if (bNeg) (-other) else other

        val r = udivrem128(aAbs.hi, aAbs.lo, bAbs.hi, bAbs.lo)
        val rem = Int128(r[2], r[3])
        return if (aNeg) -rem else rem
    }

    operator fun unaryMinus(): Int128 {
        val newLo = lo.inv() + 1L
        val carry = if (lo == 0L) 1L else 0L
        return Int128(hi.inv() + carry, newLo)
    }

    operator fun unaryPlus(): Int128 = this

    operator fun inc(): Int128 = this + ONE
    operator fun dec(): Int128 = this - ONE

    // -- Bitwise --

    infix fun and(other: Int128): Int128 = Int128(hi and other.hi, lo and other.lo)
    infix fun or(other: Int128): Int128 = Int128(hi or other.hi, lo or other.lo)
    infix fun xor(other: Int128): Int128 = Int128(hi xor other.hi, lo xor other.lo)
    fun inv(): Int128 = Int128(hi.inv(), lo.inv())

    infix fun shl(bitCount: Int): Int128 {
        val n = bitCount and 127
        return when {
            n == 0 -> this
            n < 64 -> Int128((hi shl n) or (lo ushr (64 - n)), lo shl n)
            else -> Int128(lo shl (n - 64), 0L)
        }
    }

    infix fun shr(bitCount: Int): Int128 {
        val n = bitCount and 127
        return when {
            n == 0 -> this
            n < 64 -> Int128(hi shr n, (lo ushr n) or (hi shl (64 - n)))
            else -> Int128(hi shr 63, hi shr (n - 64))
        }
    }

    infix fun ushr(bitCount: Int): Int128 {
        val n = bitCount and 127
        return when {
            n == 0 -> this
            n < 64 -> Int128(hi ushr n, (lo ushr n) or (hi shl (64 - n)))
            else -> Int128(0L, hi ushr (n - 64))
        }
    }

    // -- Comparison --

    override operator fun compareTo(other: Int128): Int {
        val hiCmp = hi.compareTo(other.hi)
        return if (hiCmp != 0) hiCmp else lo.toULong().compareTo(other.lo.toULong())
    }

    override fun equals(other: Any?): Boolean =
        other is Int128 && hi == other.hi && lo == other.lo

    override fun hashCode(): Int = 31 * hi.hashCode() + lo.hashCode()

    // -- Conversions --

    fun toByte(): Byte = lo.toByte()
    fun toShort(): Short = lo.toShort()
    fun toInt(): Int = lo.toInt()
    fun toLong(): Long = lo

    fun toFloat(): Float = toDouble().toFloat()

    fun toDouble(): Double {
        if (hi == 0L && lo >= 0) return lo.toDouble()
        if (hi == -1L && lo < 0) return lo.toDouble()
        val neg = isNegative()
        val abs = if (neg) -this else this
        val d = abs.hi.toULong().toDouble() * 18446744073709551616.0 + abs.lo.toULong().toDouble()
        return if (neg) -d else d
    }

    fun toUInt128(): UInt128 = UInt128(hi, lo)

    // -- String --

    override fun toString(): String = toString(10)

    fun toString(radix: Int): String {
        require(radix in 2..36) { "Radix must be in 2..36" }
        if (hi == 0L && lo == 0L) return "0"
        if (!isNegative()) return uToString128(hi, lo, radix)
        val abs = -this
        return "-${uToString128(abs.hi, abs.lo, radix)}"
    }

    // -- Bit utilities --

    fun countLeadingZeroBits(): Int = clz128(hi, lo)

    fun countTrailingZeroBits(): Int =
        if (lo != 0L) lo.countTrailingZeroBits() else 64 + hi.countTrailingZeroBits()

    fun countOneBits(): Int = hi.countOneBits() + lo.countOneBits()

    // -- Helpers --

    fun isNegative(): Boolean = hi < 0

    companion object {
        val ZERO = Int128(0L, 0L)
        val ONE = Int128(0L, 1L)
        val NEGATIVE_ONE = Int128(-1L, -1L)
        val MAX_VALUE = Int128(Long.MAX_VALUE, -1L)
        val MIN_VALUE = Int128(Long.MIN_VALUE, 0L)

        const val SIZE_BITS: Int = 128
        const val SIZE_BYTES: Int = 16

        fun fromLong(value: Long): Int128 =
            Int128(if (value < 0) -1L else 0L, value)

        fun fromInt(value: Int): Int128 = fromLong(value.toLong())

        fun parseString(s: String, radix: Int = 10): Int128 {
            require(s.isNotEmpty()) { "Empty string" }
            val negative = s[0] == '-'
            val start = if (s[0] == '-' || s[0] == '+') 1 else 0
            require(start < s.length) { "No digits" }
            val parts = parseUInt128(s.substring(start), radix)
            val result = Int128(parts[0], parts[1])
            return if (negative) -result else result
        }

        fun parseStringOrNull(s: String, radix: Int = 10): Int128? =
            try { parseString(s, radix) } catch (_: Exception) { null }
    }
}

// -- Extension conversions --

fun Int.toInt128(): Int128 = Int128.fromInt(this)
fun Long.toInt128(): Int128 = Int128.fromLong(this)

fun String.toInt128(radix: Int = 10): Int128 = Int128.parseString(this, radix)
fun String.toInt128OrNull(radix: Int = 10): Int128? = Int128.parseStringOrNull(this, radix)

// -- Checked arithmetic --

fun Int128.addExact(other: Int128): Int128 {
    val result = this + other
    if ((hi xor other.hi) >= 0 && (hi xor result.hi) < 0) {
        throw ArithmeticException("Int128 addition overflow")
    }
    return result
}

fun Int128.subtractExact(other: Int128): Int128 {
    val result = this - other
    if ((hi xor other.hi) < 0 && (hi xor result.hi) < 0) {
        throw ArithmeticException("Int128 subtraction overflow")
    }
    return result
}

fun Int128.negateExact(): Int128 {
    if (this == Int128.MIN_VALUE) throw ArithmeticException("Int128 negation overflow")
    return -this
}

fun Int128.abs(): Int128 {
    if (this == Int128.MIN_VALUE) throw ArithmeticException("Int128 abs overflow")
    return if (isNegative()) -this else this
}

val Int128.sign: Int
    get() = when {
        hi < 0 -> -1
        hi == 0L && lo == 0L -> 0
        else -> 1
    }

fun maxOf(a: Int128, b: Int128): Int128 = if (a >= b) a else b
fun minOf(a: Int128, b: Int128): Int128 = if (a <= b) a else b
