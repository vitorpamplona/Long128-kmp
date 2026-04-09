package com.vitorpamplona.long128.internal

/** Signed multiply-high: returns high 64 bits of the signed 128-bit product of [x] * [y]. */
internal expect fun platformMultiplyHigh(x: Long, y: Long): Long

/** Unsigned multiply-high: returns high 64 bits of the unsigned 128-bit product of [x] * [y]. */
internal expect fun platformUnsignedMultiplyHigh(x: Long, y: Long): Long
