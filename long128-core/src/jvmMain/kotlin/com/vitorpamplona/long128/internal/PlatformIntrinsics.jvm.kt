package com.vitorpamplona.long128.internal

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
 * Division has no hardware shortcut on the JVM (no `__int128`), so it falls back
 * to the pure-Kotlin implementation in [SoftwareArithmetic].
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

private fun signedMultiplyHigh(x: Long, y: Long): Long {
    val mh = SIGNED_MULTIPLY_HIGH
    return if (mh != null) mh.invokeExact(x, y) as Long else signedMultiplyHighFallback(x, y)
}

internal actual fun unsignedMultiplyHigh(x: Long, y: Long): Long {
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

internal actual fun multiply128(aHi: Long, aLo: Long, bHi: Long, bLo: Long): LongArray {
    // (aHi*2^64 + aLo) * (bHi*2^64 + bLo)  mod 2^128
    // = aLo*bLo + (aHi*bLo + aLo*bHi)*2^64  (the aHi*bHi*2^128 term overflows and is dropped)
    val resultLo = aLo * bLo
    val resultHi = unsignedMultiplyHigh(aLo, bLo) + aHi * bLo + aLo * bHi
    return longArrayOf(resultHi, resultLo)
}

internal actual fun unsignedDivide128(
    dividendHi: Long, dividendLo: Long,
    divisorHi: Long, divisorLo: Long,
): LongArray = unsignedDivRem(dividendHi, dividendLo, divisorHi, divisorLo)

internal actual fun signedDivide128(
    dividendHi: Long, dividendLo: Long,
    divisorHi: Long, divisorLo: Long,
): LongArray {
    if (divisorHi == 0L && divisorLo == 0L) throw ArithmeticException("Division by zero")
    if (dividendHi == Long.MIN_VALUE && dividendLo == 0L && divisorHi == -1L && divisorLo == -1L) {
        throw ArithmeticException("Int128 overflow: MIN_VALUE / -1")
    }

    // Convert to unsigned absolute values, divide, then fix up signs.
    // Negation is two's complement: ~x + 1.
    val aNeg = dividendHi < 0
    val bNeg = divisorHi < 0

    val aAbsLo = if (aNeg) dividendLo.inv() + 1L else dividendLo
    val aAbsHi = if (aNeg) dividendHi.inv() + (if (dividendLo == 0L) 1L else 0L) else dividendHi
    val bAbsLo = if (bNeg) divisorLo.inv() + 1L else divisorLo
    val bAbsHi = if (bNeg) divisorHi.inv() + (if (divisorLo == 0L) 1L else 0L) else divisorHi

    val result = unsignedDivRem(aAbsHi, aAbsLo, bAbsHi, bAbsLo)
    var qHi = result[0]; var qLo = result[1]
    var rHi = result[2]; var rLo = result[3]

    // Quotient is negative when operand signs differ
    if (aNeg != bNeg) {
        val negLo = qLo.inv() + 1L
        qHi = qHi.inv() + (if (qLo == 0L) 1L else 0L)
        qLo = negLo
    }
    // Remainder takes the sign of the dividend (truncated division)
    if (aNeg) {
        val negLo = rLo.inv() + 1L
        rHi = rHi.inv() + (if (rLo == 0L) 1L else 0L)
        rLo = negLo
    }

    return longArrayOf(qHi, qLo, rHi, rLo)
}
