package com.vitorpamplona.long128

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.random.Random
import kotlin.test.*

/**
 * Public multiply-high API against an independent oracle (ionspin bignum), on every
 * platform. On native this exercises the @GCUnsafeCall path; on the JVM, the
 * Math.multiplyHigh / Math.unsignedMultiplyHigh MethodHandle path.
 */
class MultiplyHighTest {

    private val TWO_64 = BigInteger.ONE.shl(64)

    /** High 64 bits of the exact product, as the two's-complement Long bit pattern. */
    private fun high64(product: BigInteger): Long {
        var hi = product.shr(64)
        // floor division for negatives: bignum shr truncates toward zero on magnitude
        if (product < BigInteger.ZERO && hi.shl(64) != product) hi -= BigInteger.ONE
        if (hi < BigInteger.ZERO) hi += TWO_64
        return hi.ulongValue(exactRequired = true).toLong()
    }

    private fun signedOracle(x: Long, y: Long) = high64(BigInteger.fromLong(x) * BigInteger.fromLong(y))
    private fun unsignedOracle(x: Long, y: Long) =
        high64(BigInteger.fromULong(x.toULong()) * BigInteger.fromULong(y.toULong()))

    private val edges = listOf(
        0L, 1L, -1L, 2L, -2L, Long.MAX_VALUE, Long.MIN_VALUE, Long.MIN_VALUE + 1,
        0xFFFFFFFFL, 0x100000000L, -0x100000000L, 0x123456789ABCDEF0L, -0x0123456789ABCDF0L,
    )

    private fun pairs(): List<Pair<Long, Long>> {
        val r = Random(0x5EED)
        return edges.flatMap { a -> edges.map { b -> a to b } } +
            List(2000) { r.nextLong() to r.nextLong() }
    }

    @Test
    fun multiplyHighMatchesOracle() {
        for ((x, y) in pairs()) {
            assertEquals(signedOracle(x, y), multiplyHigh(x, y), "multiplyHigh($x, $y)")
        }
    }

    @Test
    fun unsignedMultiplyHighMatchesOracle() {
        for ((x, y) in pairs()) {
            assertEquals(unsignedOracle(x, y), unsignedMultiplyHigh(x, y), "unsignedMultiplyHigh($x, $y)")
        }
    }

    @Test
    fun uLongMultiplyHighMatchesOracle() {
        for ((x, y) in pairs()) {
            assertEquals(unsignedOracle(x, y).toULong(), x.toULong().multiplyHigh(y.toULong()))
        }
    }

    @Test
    fun knownValues() {
        assertEquals(-2L, unsignedMultiplyHigh(-1L, -1L))           // (2^64-1)^2 >> 64 = 2^64 - 2
        assertEquals(0L, multiplyHigh(-1L, -1L))                    // (-1)(-1) = 1
        assertEquals(-1L, multiplyHigh(-1L, 1L))                    // -1 sign-extends
        assertEquals(1L shl 62, multiplyHigh(Long.MIN_VALUE, Long.MIN_VALUE))   // 2^126 >> 64
        assertEquals(1L shl 62, unsignedMultiplyHigh(Long.MIN_VALUE, Long.MIN_VALUE))
    }
}
