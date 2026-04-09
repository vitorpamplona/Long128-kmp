package com.vitorpamplona.long128.internal

internal actual fun platformMultiplyHigh(x: Long, y: Long): Long =
    Math.multiplyHigh(x, y)

internal actual fun platformUnsignedMultiplyHigh(x: Long, y: Long): Long =
    Math.multiplyHigh(x, y) + (x and (y shr 63)) + (y and (x shr 63))
