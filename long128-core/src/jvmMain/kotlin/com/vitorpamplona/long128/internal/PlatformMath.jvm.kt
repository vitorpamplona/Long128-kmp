package com.vitorpamplona.long128.internal

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * JVM platform math with tiered optimization.
 *
 * Resolves Math.multiplyHigh (JDK 9+) and Math.unsignedMultiplyHigh (JDK 18+)
 * via MethodHandle at class-load time. This:
 *
 * 1. Avoids D8 desugaring on Android (D8 scans for invokestatic, not invokeExact)
 * 2. Allows HotSpot C2 to inline the constant MethodHandle → same perf as direct call
 * 3. Automatically picks the best available intrinsic at runtime
 *
 * Tier priority:
 * - JDK 18+ / Android API 35+(?): Math.unsignedMultiplyHigh → single MUL/UMULH
 * - JDK 9+  / Android API 31+:    Math.multiplyHigh + 3-insn correction
 * - JDK 8   / Android API <31:    Karatsuba 4-imul fallback
 */

private val JJ_J = MethodType.methodType(Long::class.java, Long::class.java, Long::class.java)

/** Math.multiplyHigh(long, long) → signed high 64 bits. JDK 9+. */
private val MH_MULTIPLY_HIGH: java.lang.invoke.MethodHandle? = try {
    MethodHandles.lookup().findStatic(Math::class.java, "multiplyHigh", JJ_J)
} catch (_: Throwable) { null }

/** Math.unsignedMultiplyHigh(long, long) → unsigned high 64 bits. JDK 18+. */
private val MH_UNSIGNED_MULTIPLY_HIGH: java.lang.invoke.MethodHandle? = try {
    MethodHandles.lookup().findStatic(Math::class.java, "unsignedMultiplyHigh", JJ_J)
} catch (_: Throwable) { null }

/**
 * Pure-Kotlin signed multiply-high (Hacker's Delight / JDK 9 algorithm).
 * Used when Math.multiplyHigh is unavailable (JDK 8, Android API <31).
 */
internal fun multiplyHighFallback(x: Long, y: Long): Long {
    val x1 = x shr 32
    val x0 = x and 0xFFFFFFFFL
    val y1 = y shr 32
    val y0 = y and 0xFFFFFFFFL

    val z2 = x1 * y1
    val t = x1 * y0 + (x0 * y0).ushr(32)
    val z1 = (t and 0xFFFFFFFFL) + x0 * y1

    return z2 + (t shr 32) + (z1 shr 32)
}

private fun signedMultiplyHigh(x: Long, y: Long): Long {
    val mh = MH_MULTIPLY_HIGH
    return if (mh != null) {
        mh.invokeExact(x, y) as Long
    } else {
        multiplyHighFallback(x, y)
    }
}

/**
 * Unsigned multiply-high.
 *
 * Tier 1 (JDK 18+): Math.unsignedMultiplyHigh → single MUL (x86) / UMULH (ARM64)
 * Tier 2 (JDK 9+):  Math.multiplyHigh + signed-to-unsigned correction (3 extra insns)
 * Tier 3 (JDK 8):   Karatsuba fallback + correction
 */
internal actual fun platformUnsignedMultiplyHigh(x: Long, y: Long): Long {
    val mh = MH_UNSIGNED_MULTIPLY_HIGH
    if (mh != null) {
        return mh.invokeExact(x, y) as Long
    }
    // Fall back to signed + correction
    val signed = signedMultiplyHigh(x, y)
    return signed + (x and (y shr 63)) + (y and (x shr 63))
}

/** JVM: 128×128 multiply using unsignedMultiplyHigh for the cross-word product. */
internal actual fun platformMul128(aHi: Long, aLo: Long, bHi: Long, bLo: Long): LongArray {
    val lo = aLo * bLo
    val hi = platformUnsignedMultiplyHigh(aLo, bLo) + aHi * bLo + aLo * bHi
    return longArrayOf(hi, lo)
}

/** JVM: division via pure-Kotlin algorithm (no __int128 available). */
internal actual fun platformUDivRem128(nHi: Long, nLo: Long, dHi: Long, dLo: Long): LongArray =
    udivrem128(nHi, nLo, dHi, dLo)

internal actual fun platformSDivRem128(nHi: Long, nLo: Long, dHi: Long, dLo: Long): LongArray {
    if (dHi == 0L && dLo == 0L) throw ArithmeticException("Division by zero")
    if (nHi == Long.MIN_VALUE && nLo == 0L && dHi == -1L && dLo == -1L) {
        throw ArithmeticException("Int128 overflow: MIN_VALUE / -1")
    }

    val aNeg = nHi < 0
    val bNeg = dHi < 0
    val aAbsLo = if (aNeg) nLo.inv() + 1L else nLo
    val aAbsHi = if (aNeg) nHi.inv() + (if (nLo == 0L) 1L else 0L) else nHi
    val bAbsLo = if (bNeg) dLo.inv() + 1L else dLo
    val bAbsHi = if (bNeg) dHi.inv() + (if (dLo == 0L) 1L else 0L) else dHi

    val r = udivrem128(aAbsHi, aAbsLo, bAbsHi, bAbsLo)
    var qHi = r[0]; var qLo = r[1]
    var rHi = r[2]; var rLo = r[3]

    if (aNeg != bNeg) {
        val newQLo = qLo.inv() + 1L
        qHi = qHi.inv() + (if (qLo == 0L) 1L else 0L)
        qLo = newQLo
    }
    if (aNeg) {
        val newRLo = rLo.inv() + 1L
        rHi = rHi.inv() + (if (rLo == 0L) 1L else 0L)
        rLo = newRLo
    }

    return longArrayOf(qHi, qLo, rHi, rLo)
}
