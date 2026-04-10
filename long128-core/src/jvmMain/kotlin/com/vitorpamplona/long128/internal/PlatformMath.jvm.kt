package com.vitorpamplona.long128.internal

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * JVM platform math.
 *
 * Uses MethodHandle to resolve Math.multiplyHigh at runtime so D8 on Android
 * cannot desugar it. On HotSpot (desktop JVM), C2 inlines constant MethodHandles,
 * so the performance is near-identical to a direct invokestatic.
 *
 * On API < 31 (Android) or JDK < 9, falls back to the Karatsuba 4-imul
 * decomposition.
 */

private val MULTIPLY_HIGH: java.lang.invoke.MethodHandle? = try {
    MethodHandles.lookup().findStatic(
        Math::class.java,
        "multiplyHigh",
        MethodType.methodType(Long::class.java, Long::class.java, Long::class.java)
    )
} catch (_: Throwable) {
    null
}

/** Pure-Kotlin signed multiply-high (Hacker's Delight / JDK 9 algorithm). */
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

private fun multiplyHigh(x: Long, y: Long): Long {
    val mh = MULTIPLY_HIGH
    return if (mh != null) {
        mh.invokeExact(x, y) as Long
    } else {
        multiplyHighFallback(x, y)
    }
}

internal actual fun platformUnsignedMultiplyHigh(x: Long, y: Long): Long {
    val signed = multiplyHigh(x, y)
    return signed + (x and (y shr 63)) + (y and (x shr 63))
}

/** JVM: 128×128 multiply in pure Kotlin (no hardware shortcut beyond multiplyHigh). */
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
    val aAbsHi = if (aNeg) { val lo = nLo.inv() + 1L; nHi.inv() + (if (nLo == 0L) 1L else 0L) } else nHi
    val aAbsLo = if (aNeg) nLo.inv() + 1L else nLo
    val bAbsHi = if (bNeg) { val lo = dLo.inv() + 1L; dHi.inv() + (if (dLo == 0L) 1L else 0L) } else dHi
    val bAbsLo = if (bNeg) dLo.inv() + 1L else dLo

    val r = udivrem128(aAbsHi, aAbsLo, bAbsHi, bAbsLo)
    var qHi = r[0]; var qLo = r[1]
    var rHi = r[2]; var rLo = r[3]

    if (aNeg != bNeg) {
        // Negate quotient
        val newLo = qLo.inv() + 1L
        qHi = qHi.inv() + (if (qLo == 0L) 1L else 0L)
        qLo = newLo
    }
    if (aNeg) {
        // Negate remainder
        val newLo = rLo.inv() + 1L
        rHi = rHi.inv() + (if (rLo == 0L) 1L else 0L)
        rLo = newLo
    }

    return longArrayOf(qHi, qLo, rHi, rLo)
}
