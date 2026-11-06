package com.vitorpamplona.long128.internal

@PublishedApi internal actual class Bits128(@JvmField val hi: Long, @JvmField val lo: Long) {
    override fun equals(other: Any?): Boolean = other is Bits128 && hi == other.hi && lo == other.lo
    override fun hashCode(): Int = 31 * hi.hashCode() + lo.hashCode()
}

@PublishedApi internal actual fun bits128(hi: Long, lo: Long): Bits128 = Bits128(hi, lo)

@PublishedApi internal actual fun Bits128.hiWord(): Long = hi

@PublishedApi internal actual fun Bits128.loWord(): Long = lo
