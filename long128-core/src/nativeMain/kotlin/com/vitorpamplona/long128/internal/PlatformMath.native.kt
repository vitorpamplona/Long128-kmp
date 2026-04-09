package com.vitorpamplona.long128.internal

import int128ops.int128_multiply_high_signed
import int128ops.int128_multiply_high_unsigned

internal actual fun platformMultiplyHigh(x: Long, y: Long): Long =
    int128_multiply_high_signed(x, y)

internal actual fun platformUnsignedMultiplyHigh(x: Long, y: Long): Long =
    int128_multiply_high_unsigned(x, y)
