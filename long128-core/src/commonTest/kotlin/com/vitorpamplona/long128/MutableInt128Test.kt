package com.vitorpamplona.long128

import kotlin.math.sign
import kotlin.random.Random
import kotlin.test.*

/**
 * MutableUInt128 / MutableInt128 against the immutable operations (themselves checked
 * against a bignum oracle in CrossPlatformOracleTest and DivisionTest).
 */
class MutableInt128Test {

    private val r = Random(0x3A7)

    private fun u(): UInt128 = when (r.nextInt(4)) {
        0 -> UInt128(0L, r.nextLong())
        1 -> UInt128(r.nextLong() ushr r.nextInt(64), r.nextLong())
        2 -> UInt128(-1L, r.nextLong())
        else -> UInt128(r.nextLong(), r.nextLong())
    }
    private fun s(): Int128 = u().toInt128()

    @Test fun unsignedOpsMatchImmutable() {
        repeat(3000) {
            val x = u(); val y = u(); val shift = r.nextInt(200)
            fun m() = MutableUInt128(x)
            assertEquals(x + y, m().apply { this += y }.toUInt128())
            assertEquals(x - y, m().apply { this -= y }.toUInt128())
            assertEquals(x * y, m().apply { this *= y }.toUInt128())
            assertEquals(x * y + x, m().apply { multiplyAdd(y, x) }.toUInt128())
            assertEquals(x and y, m().apply { andAssign(y) }.toUInt128())
            assertEquals(x or y, m().apply { orAssign(y) }.toUInt128())
            assertEquals(x xor y, m().apply { xorAssign(y) }.toUInt128())
            assertEquals(x shl shift, m().apply { shiftLeft(shift) }.toUInt128())
            assertEquals(x shr shift, m().apply { shiftRight(shift) }.toUInt128())
            assertEquals(x.compareTo(y).sign, m().compareTo(y).sign)
            if (y != UInt128.ZERO) {
                assertEquals(x / y, m().apply { this /= y }.toUInt128())
                assertEquals(x % y, m().apply { this %= y }.toUInt128())
            }
        }
    }

    @Test fun signedOpsMatchImmutable() {
        repeat(3000) {
            val x = s(); val y = s(); val shift = r.nextInt(200)
            fun m() = MutableInt128(x)
            assertEquals(x + y, m().apply { this += y }.toInt128())
            assertEquals(x - y, m().apply { this -= y }.toInt128())
            assertEquals(x * y, m().apply { this *= y }.toInt128())
            assertEquals(x * y + x, m().apply { multiplyAdd(y, x) }.toInt128())
            assertEquals(-x, m().apply { negate() }.toInt128())
            assertEquals(x shl shift, m().apply { shiftLeft(shift) }.toInt128())
            assertEquals(x shr shift, m().apply { shiftRight(shift) }.toInt128())
            assertEquals(x.compareTo(y).sign, m().compareTo(y).sign)
            if (y != Int128.ZERO && !(x == Int128.MIN_VALUE && y == Int128.NEGATIVE_ONE)) {
                assertEquals(x / y, m().apply { this /= y }.toInt128())
                assertEquals(x % y, m().apply { this %= y }.toInt128())
            }
        }
    }

    @Test fun accumulatorChainMatchesImmutableChain() {
        val a = UInt128(0x12345678L, 0x9ABCDEF0L)
        val b = UInt128(0L, -0x0123456789ABCDF0L)
        val m = UInt128(0L, 1000000007L)
        var acc = UInt128.ONE
        val macc = MutableUInt128(UInt128.ONE)
        for (i in 0 until 10_000) {
            acc = acc * a + b
            macc.multiplyAdd(a, b)
            if ((i and 0xFF) == 0) { acc %= m; macc %= m }
        }
        assertEquals(acc, macc.toUInt128())
        assertEquals(acc.toString(), macc.toString())
    }

    @Test fun edgesAndErrors() {
        assertFailsWith<ArithmeticException> { MutableUInt128(UInt128.ONE).apply { this /= UInt128.ZERO } }
        assertFailsWith<ArithmeticException> { MutableInt128(Int128.ONE).apply { this %= Int128.ZERO } }
        assertFailsWith<ArithmeticException> { MutableInt128(Int128.MIN_VALUE).apply { this /= Int128.NEGATIVE_ONE } }
        assertEquals(Int128.ZERO, MutableInt128(Int128.MIN_VALUE).apply { this %= Int128.NEGATIVE_ONE }.toInt128())
        assertEquals(Int128.MIN_VALUE, MutableInt128(Int128.MIN_VALUE).apply { negate() }.toInt128())
        assertEquals(UInt128.ZERO, MutableUInt128(UInt128.MAX_VALUE).apply { this += UInt128.ONE }.toUInt128())
        assertEquals(MutableUInt128(1L, 2L), MutableUInt128(UInt128(1L, 2L)))
        assertEquals(MutableUInt128(1L, 2L).hashCode(), MutableUInt128(1L, 2L).hashCode())
    }
}
