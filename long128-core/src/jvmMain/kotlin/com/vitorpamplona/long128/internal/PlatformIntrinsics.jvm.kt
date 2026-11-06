package com.vitorpamplona.long128.internal

import com.vitorpamplona.long128.Int128
import com.vitorpamplona.long128.UInt128
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * JVM intrinsics for 128-bit arithmetic.
 *
 * Resolves `Math.multiplyHigh` and `Math.unsignedMultiplyHigh` (JDK 18+) via
 * [MethodHandle] at class-load time rather than `invokestatic`. This is necessary
 * because Android's D8 tool scans bytecode for `invokestatic Math.multiplyHigh` and
 * replaces it with a pure-Java backport when the app's `minSdk < 31` — even if the
 * library itself targets a higher API. MethodHandle dispatch is invisible to D8.
 *
 * On HotSpot (desktop JVM), C2 inlines constant MethodHandle fields, so there is
 * no performance penalty compared to a direct `invokestatic`.
 *
 * Division uses Knuth's algorithm D on 64-bit hardware division ([unsignedQuotient] / [unsignedRemainder] in
 * SoftwareArithmetic.kt): the JVM has no 128-bit division instruction to call.
 */

private val LONG_LONG_TO_LONG = MethodType.methodType(
    Long::class.java, Long::class.java, Long::class.java
)

/**
 * `Math.multiplyHigh(long, long)` → signed high 64 bits of 128-bit product.
 * Always present on our minimum JDK 17. HotSpot intrinsifies to `IMUL` (x86) / `SMULH` (ARM).
 */
private val SIGNED_MULTIPLY_HIGH: java.lang.invoke.MethodHandle? = try {
    MethodHandles.lookup().findStatic(Math::class.java, "multiplyHigh", LONG_LONG_TO_LONG)
} catch (_: Throwable) { null }

/**
 * `Math.unsignedMultiplyHigh(long, long)` → unsigned high 64 bits.
 * Available on JDK 18+. HotSpot intrinsifies to `MUL` (x86) / `UMULH` (ARM).
 * Eliminates the 3-instruction signed-to-unsigned correction.
 */
private val UNSIGNED_MULTIPLY_HIGH: java.lang.invoke.MethodHandle? = try {
    MethodHandles.lookup().findStatic(Math::class.java, "unsignedMultiplyHigh", LONG_LONG_TO_LONG)
} catch (_: Throwable) { null }

/**
 * Pure-Kotlin signed multiply-high, used when `Math.multiplyHigh` is unavailable.
 *
 * Decomposes each 64-bit operand into two 32-bit halves and computes four partial
 * products (the "Karatsuba" pattern). This is the same algorithm used by
 * `Math.multiplyHigh`'s Java implementation before HotSpot intrinsification.
 *
 * Reference: Hacker's Delight, section 8-2.
 */
internal fun signedMultiplyHighFallback(x: Long, y: Long): Long {
    val x1 = x shr 32           // signed high half
    val x0 = x and 0xFFFFFFFFL  // unsigned low half (zero-extended)
    val y1 = y shr 32
    val y0 = y and 0xFFFFFFFFL

    val highProduct = x1 * y1
    val crossSum = x1 * y0 + (x0 * y0).ushr(32)
    val crossAdjusted = (crossSum and 0xFFFFFFFFL) + x0 * y1

    return highProduct + (crossSum shr 32) + (crossAdjusted shr 32)
}

internal actual fun signedMultiplyHigh(x: Long, y: Long): Long {
    val mh = SIGNED_MULTIPLY_HIGH
    return if (mh != null) mh.invokeExact(x, y) as Long else signedMultiplyHighFallback(x, y)
}

@PublishedApi internal actual fun unsignedMultiplyHigh(x: Long, y: Long): Long {
    // Tier 1: direct unsigned intrinsic (JDK 18+)
    val umh = UNSIGNED_MULTIPLY_HIGH
    if (umh != null) return umh.invokeExact(x, y) as Long

    // Tier 2/3: signed multiply-high + unsigned correction.
    // The correction accounts for the difference between signed and unsigned
    // interpretation: unsigned(x) = signed(x) + 2^64 * (x < 0 ? 1 : 0).
    // Expanding the product and taking the high word gives:
    //   unsignedHigh = signedHigh + (x < 0 ? y : 0) + (y < 0 ? x : 0)
    // The expression (x and (y shr 63)) equals x when y is negative (shr 63 = all 1s)
    // and 0 when y is non-negative (shr 63 = all 0s).
    val signed = signedMultiplyHigh(x, y)
    return signed + (x and (y shr 63)) + (y and (x shr 63))
}

internal actual fun unsignedQuotient128(
    dividendHi: Long, dividendLo: Long, divisorHi: Long, divisorLo: Long,
): UInt128 = unsignedQuotient(dividendHi, dividendLo, divisorHi, divisorLo)

internal actual fun unsignedRemainder128(
    dividendHi: Long, dividendLo: Long, divisorHi: Long, divisorLo: Long,
): UInt128 = unsignedRemainder(dividendHi, dividendLo, divisorHi, divisorLo)

// Signed division divides the magnitudes, then fixes the signs: the quotient is
// negative when the operand signs differ, and the remainder takes the dividend's
// sign (truncated division, like Kotlin's `/` and `%` on Long).

private fun absHi(hi: Long, lo: Long): Long = if (hi < 0) hi.inv() + (if (lo == 0L) 1L else 0L) else hi
private fun absLo(hi: Long, lo: Long): Long = if (hi < 0) -lo else lo
// One construction site (see the note above unsignedQuotient in SoftwareArithmetic.kt).
private fun negate(x: UInt128, negative: Boolean): Int128 = Int128(
    if (negative) x.hi.inv() + (if (x.lo == 0L) 1L else 0L) else x.hi,
    if (negative) -x.lo else x.lo,
)

internal actual fun signedQuotient128(
    dividendHi: Long, dividendLo: Long, divisorHi: Long, divisorLo: Long,
): Int128 {
    if (dividendHi == Long.MIN_VALUE && dividendLo == 0L && divisorHi == -1L && divisorLo == -1L) {
        throw ArithmeticException("Int128 overflow: MIN_VALUE / -1")
    }
    val q = unsignedQuotient(
        absHi(dividendHi, dividendLo), absLo(dividendHi, dividendLo),
        absHi(divisorHi, divisorLo), absLo(divisorHi, divisorLo),
    )
    return negate(q, (dividendHi < 0) != (divisorHi < 0))
}

internal actual fun signedRemainder128(
    dividendHi: Long, dividendLo: Long, divisorHi: Long, divisorLo: Long,
): Int128 {
    // MIN_VALUE % -1 == 0, as for Long (the quotient overflows, the remainder does not).
    if (dividendHi == Long.MIN_VALUE && dividendLo == 0L && divisorHi == -1L && divisorLo == -1L) return Int128.ZERO
    val r = unsignedRemainder(
        absHi(dividendHi, dividendLo), absLo(dividendHi, dividendLo),
        absHi(divisorHi, divisorLo), absLo(divisorHi, divisorLo),
    )
    return negate(r, dividendHi < 0)
}

internal actual fun divideUnsigned64(dividend: Long, divisor: Long): Long =
    java.lang.Long.divideUnsigned(dividend, divisor)
