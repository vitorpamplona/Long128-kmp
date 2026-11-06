package com.vitorpamplona.long128

import com.vitorpamplona.long128.internal.multiply128High
import com.vitorpamplona.long128.internal.signedQuotient128
import com.vitorpamplona.long128.internal.signedRemainder128
import com.vitorpamplona.long128.internal.unsignedQuotient128
import com.vitorpamplona.long128.internal.unsignedRemainder128

/**
 * A mutable unsigned 128-bit accumulator, for loop-carried computations on the JVM.
 *
 * ## Why it exists
 *
 * On the JVM an [UInt128] result is an object. When a value is carried from one
 * loop iteration to the next (`acc = acc * a + b`), HotSpot's escape analysis
 * cannot remove that object, because it flows through the loop's back edge: every
 * iteration allocates. (Arrays and straight-line code are fine; there the objects
 * are scalar-replaced.) Kotlin has no multi-field value classes yet to fix this.
 *
 * A `MutableUInt128` is allocated once, before the loop, and updated in place:
 *
 * ```kotlin
 * val acc = MutableUInt128(UInt128.ONE)
 * for (i in 0 until n) { acc *= a; acc += b }
 * val result = acc.toUInt128()
 * ```
 *
 * When it does not escape the method, escape analysis replaces it by its two
 * fields, so the loop runs on two `long` registers with no allocation at all,
 * like C's `unsigned __int128`.
 *
 * Operands are ordinary [UInt128] values. On Kotlin/Native, `UInt128` already
 * never allocates; this class works there too.
 */
class MutableUInt128(hi: Long, lo: Long) {

    /** Most-significant 64 bits (read as unsigned). */
    var hi: Long = hi
        private set

    /** Least-significant 64 bits (read as unsigned). */
    var lo: Long = lo
        private set

    constructor() : this(0L, 0L)
    constructor(value: UInt128) : this(value.hi, value.lo)

    fun set(value: UInt128) { hi = value.hi; lo = value.lo }
    fun set(hi: Long, lo: Long) { this.hi = hi; this.lo = lo }

    fun toUInt128(): UInt128 = UInt128(hi, lo)

    operator fun plusAssign(other: UInt128) {
        val l = lo + other.lo
        hi = hi + other.hi + (if ((l xor Long.MIN_VALUE) < (lo xor Long.MIN_VALUE)) 1L else 0L)
        lo = l
    }

    operator fun minusAssign(other: UInt128) {
        val l = lo - other.lo
        hi = hi - other.hi - (if ((lo xor Long.MIN_VALUE) < (other.lo xor Long.MIN_VALUE)) 1L else 0L)
        lo = l
    }

    operator fun timesAssign(other: UInt128) {
        val h = multiply128High(hi, lo, other.hi, other.lo)
        lo *= other.lo
        hi = h
    }

    operator fun divAssign(other: UInt128) = set(unsignedQuotient128(hi, lo, other.hi, other.lo))

    operator fun remAssign(other: UInt128) = set(unsignedRemainder128(hi, lo, other.hi, other.lo))

    /** `this = this * a + b`, wrapping. */
    fun multiplyAdd(a: UInt128, b: UInt128) {
        timesAssign(a)
        plusAssign(b)
    }

    fun andAssign(other: UInt128) { hi = hi and other.hi; lo = lo and other.lo }
    fun orAssign(other: UInt128) { hi = hi or other.hi; lo = lo or other.lo }
    fun xorAssign(other: UInt128) { hi = hi xor other.hi; lo = lo xor other.lo }

    /** `this = this shl bitCount` (masked to 0..127). */
    fun shiftLeft(bitCount: Int) {
        val n = bitCount and 127
        when {
            n == 0 -> {}
            n < 64 -> { hi = (hi shl n) or (lo ushr (64 - n)); lo = lo shl n }
            else -> { hi = lo shl (n - 64); lo = 0L }
        }
    }

    /** `this = this ushr bitCount` (logical, masked to 0..127). */
    fun shiftRight(bitCount: Int) {
        val n = bitCount and 127
        when {
            n == 0 -> {}
            n < 64 -> { lo = (lo ushr n) or (hi shl (64 - n)); hi = hi ushr n }
            else -> { lo = hi ushr (n - 64); hi = 0L }
        }
    }

    /** Unsigned comparison with an immutable value, so `acc < limit` works. */
    operator fun compareTo(other: UInt128): Int {
        val c = (hi xor Long.MIN_VALUE).compareTo(other.hi xor Long.MIN_VALUE)
        return if (c != 0) c else (lo xor Long.MIN_VALUE).compareTo(other.lo xor Long.MIN_VALUE)
    }

    override fun equals(other: Any?): Boolean = other is MutableUInt128 && hi == other.hi && lo == other.lo
    override fun hashCode(): Int = 31 * hi.hashCode() + lo.hashCode()
    override fun toString(): String = toUInt128().toString()
}

/**
 * A mutable signed 128-bit accumulator: the [Int128] counterpart of [MutableUInt128].
 * See there for why it exists and how to use it.
 */
class MutableInt128(hi: Long, lo: Long) {

    /** Most-significant 64 bits (signed). */
    var hi: Long = hi
        private set

    /** Least-significant 64 bits (read as unsigned). */
    var lo: Long = lo
        private set

    constructor() : this(0L, 0L)
    constructor(value: Int128) : this(value.hi, value.lo)

    fun set(value: Int128) { hi = value.hi; lo = value.lo }
    fun set(hi: Long, lo: Long) { this.hi = hi; this.lo = lo }

    fun toInt128(): Int128 = Int128(hi, lo)

    operator fun plusAssign(other: Int128) {
        val l = lo + other.lo
        hi = hi + other.hi + (if ((l xor Long.MIN_VALUE) < (lo xor Long.MIN_VALUE)) 1L else 0L)
        lo = l
    }

    operator fun minusAssign(other: Int128) {
        val l = lo - other.lo
        hi = hi - other.hi - (if ((lo xor Long.MIN_VALUE) < (other.lo xor Long.MIN_VALUE)) 1L else 0L)
        lo = l
    }

    operator fun timesAssign(other: Int128) {
        val h = multiply128High(hi, lo, other.hi, other.lo)
        lo *= other.lo
        hi = h
    }

    /** Truncated division. @throws ArithmeticException on zero, or MIN_VALUE / -1. */
    operator fun divAssign(other: Int128) = set(signedQuotient128(hi, lo, other.hi, other.lo))

    /** Remainder with the dividend's sign. @throws ArithmeticException on zero. */
    operator fun remAssign(other: Int128) = set(signedRemainder128(hi, lo, other.hi, other.lo))

    /** `this = this * a + b`, wrapping. */
    fun multiplyAdd(a: Int128, b: Int128) {
        timesAssign(a)
        plusAssign(b)
    }

    /** `this = -this` (two's complement; `MIN_VALUE` stays `MIN_VALUE`). */
    fun negate() {
        hi = hi.inv() + (if (lo == 0L) 1L else 0L)
        lo = -lo
    }

    fun andAssign(other: Int128) { hi = hi and other.hi; lo = lo and other.lo }
    fun orAssign(other: Int128) { hi = hi or other.hi; lo = lo or other.lo }
    fun xorAssign(other: Int128) { hi = hi xor other.hi; lo = lo xor other.lo }

    /** `this = this shl bitCount` (masked to 0..127). */
    fun shiftLeft(bitCount: Int) {
        val n = bitCount and 127
        when {
            n == 0 -> {}
            n < 64 -> { hi = (hi shl n) or (lo ushr (64 - n)); lo = lo shl n }
            else -> { hi = lo shl (n - 64); lo = 0L }
        }
    }

    /** `this = this shr bitCount` (arithmetic, sign-filling; masked to 0..127). */
    fun shiftRight(bitCount: Int) {
        val n = bitCount and 127
        when {
            n == 0 -> {}
            n < 64 -> { lo = (lo ushr n) or (hi shl (64 - n)); hi = hi shr n }
            else -> { lo = hi shr (n - 64); hi = hi shr 63 }
        }
    }

    /** Signed comparison with an immutable value, so `acc < limit` works. */
    operator fun compareTo(other: Int128): Int {
        val c = hi.compareTo(other.hi)
        return if (c != 0) c else (lo xor Long.MIN_VALUE).compareTo(other.lo xor Long.MIN_VALUE)
    }

    override fun equals(other: Any?): Boolean = other is MutableInt128 && hi == other.hi && lo == other.lo
    override fun hashCode(): Int = 31 * hi.hashCode() + lo.hashCode()
    override fun toString(): String = toInt128().toString()
}
