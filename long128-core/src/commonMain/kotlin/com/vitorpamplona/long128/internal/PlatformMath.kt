package com.vitorpamplona.long128.internal

/** Unsigned multiply-high: returns high 64 bits of the unsigned 128-bit product of [x] * [y]. */
internal expect fun platformUnsignedMultiplyHigh(x: Long, y: Long): Long

/**
 * Full 128×128 wrapping multiply.
 * Returns the low 128 bits of the product as (hi, lo) packed in a LongArray.
 * Platform implementations may override this with a single hardware-accelerated call.
 */
internal expect fun platformMul128(aHi: Long, aLo: Long, bHi: Long, bLo: Long): LongArray

/**
 * Unsigned 128÷128 division + remainder.
 * Returns LongArray [qHi, qLo, rHi, rLo].
 * Platform implementations may override with hardware __int128 division.
 */
internal expect fun platformUDivRem128(nHi: Long, nLo: Long, dHi: Long, dLo: Long): LongArray

/**
 * Signed 128÷128 division.
 * Returns LongArray [qHi, qLo].
 * Platform implementations may override with hardware __int128 division.
 */
internal expect fun platformSDivRem128(nHi: Long, nLo: Long, dHi: Long, dLo: Long): LongArray
