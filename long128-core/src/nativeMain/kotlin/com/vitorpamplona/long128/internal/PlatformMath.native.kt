package com.vitorpamplona.long128.internal

import int128ops.*
import kotlinx.cinterop.useContents

internal actual fun platformUnsignedMultiplyHigh(x: Long, y: Long): Long =
    int128_multiply_high_unsigned(x, y)

/**
 * Native: full 128×128 multiply via __int128 in C.
 * One cinterop call → 5 straight-line x86 instructions (imul, imul, mul, add, add)
 * or 4 ARM64 instructions (mul, umulh, madd, madd).
 */
internal actual fun platformMul128(aHi: Long, aLo: Long, bHi: Long, bLo: Long): LongArray {
    val result = int128_mul(aHi, aLo, bHi, bLo)
    return longArrayOf(result.useContents { hi }, result.useContents { lo })
}

/**
 * Native: unsigned 128÷128 division via unsigned __int128 in C.
 * Uses compiler's optimized __udivti3 runtime instead of O(128) Kotlin loop.
 */
internal actual fun platformUDivRem128(nHi: Long, nLo: Long, dHi: Long, dLo: Long): LongArray {
    if (dHi == 0L && dLo == 0L) throw ArithmeticException("Division by zero")
    val q = uint128_div(nHi, nLo, dHi, dLo)
    val r = uint128_rem(nHi, nLo, dHi, dLo)
    return longArrayOf(
        q.useContents { hi }, q.useContents { lo },
        r.useContents { hi }, r.useContents { lo }
    )
}

/**
 * Native: signed 128÷128 division via __int128 in C.
 * Uses compiler's optimized __divti3 runtime.
 */
internal actual fun platformSDivRem128(nHi: Long, nLo: Long, dHi: Long, dLo: Long): LongArray {
    if (dHi == 0L && dLo == 0L) throw ArithmeticException("Division by zero")
    if (nHi == Long.MIN_VALUE && nLo == 0L && dHi == -1L && dLo == -1L) {
        throw ArithmeticException("Int128 overflow: MIN_VALUE / -1")
    }
    val q = int128_sdiv(nHi, nLo, dHi, dLo)
    val r = int128_srem(nHi, nLo, dHi, dLo)
    return longArrayOf(
        q.useContents { hi }, q.useContents { lo },
        r.useContents { hi }, r.useContents { lo }
    )
}
