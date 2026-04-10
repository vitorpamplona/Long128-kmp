package com.vitorpamplona.long128.internal

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

/**
 * JVM platform math using MethodHandle to avoid D8 desugaring on Android.
 *
 * D8 scans bytecode for `invokestatic Math.multiplyHigh` and replaces it with
 * a pure-Java backport when the app's minSdk < 31. By resolving the method via
 * MethodHandle at class-load time, the bytecode contains `invokeExact` on a
 * MethodHandle instead, which D8 does not intercept. ART on API 31+ still
 * intrinsifies the underlying Math.multiplyHigh to SMULH/IMUL.
 *
 * On API < 31 (or JDK < 9), the handle is null and we fall back to the
 * Karatsuba decomposition — the same 4-imul software path, but without the
 * overhead of D8's backport wrapper.
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

/**
 * Pure-Kotlin signed multiply-high fallback (Hacker's Delight / JDK 9 algorithm).
 * Computes the upper 64 bits of the signed 128-bit product of x * y.
 */
private fun multiplyHighFallback(x: Long, y: Long): Long {
    val x1 = x shr 32
    val x0 = x and 0xFFFFFFFFL
    val y1 = y shr 32
    val y0 = y and 0xFFFFFFFFL

    val z2 = x1 * y1
    val t = x1 * y0 + (x0 * y0).ushr(32)
    val z1 = (t and 0xFFFFFFFFL) + x0 * y1

    return z2 + (t shr 32) + (z1 shr 32)
}

internal actual fun platformMultiplyHigh(x: Long, y: Long): Long {
    val mh = MULTIPLY_HIGH
    return if (mh != null) {
        mh.invokeExact(x, y) as Long
    } else {
        multiplyHighFallback(x, y)
    }
}

internal actual fun platformUnsignedMultiplyHigh(x: Long, y: Long): Long {
    val signed = platformMultiplyHigh(x, y)
    return signed + (x and (y shr 63)) + (y and (x shr 63))
}
