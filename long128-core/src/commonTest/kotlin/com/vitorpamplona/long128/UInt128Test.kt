package com.vitorpamplona.long128

import kotlin.test.*

class UInt128Test {

    @Test fun zeroIsZero() {
        assertEquals(0L, UInt128.ZERO.hi)
        assertEquals(0L, UInt128.ZERO.lo)
    }

    @Test fun oneIsOne() {
        assertEquals(0L, UInt128.ONE.hi)
        assertEquals(1L, UInt128.ONE.lo)
    }

    @Test fun maxValueAllBits() {
        assertEquals(-1L, UInt128.MAX_VALUE.hi)
        assertEquals(-1L, UInt128.MAX_VALUE.lo)
    }

    @Test fun addSmall() {
        assertEquals(UInt128.fromLong(30), UInt128.fromLong(10) + UInt128.fromLong(20))
    }

    @Test fun addWithCarry() {
        assertEquals(UInt128(1L, 0L), UInt128(0L, -1L) + UInt128.ONE)
    }

    @Test fun addOverflowWraps() {
        assertEquals(UInt128.ZERO, UInt128.MAX_VALUE + UInt128.ONE)
    }

    @Test fun subSmall() {
        assertEquals(UInt128.fromLong(5), UInt128.fromLong(15) - UInt128.fromLong(10))
    }

    @Test fun subUnderflowWraps() {
        assertEquals(UInt128.MAX_VALUE, UInt128.ZERO - UInt128.ONE)
    }

    @Test fun mulSmall() {
        assertEquals(UInt128.fromLong(42), UInt128.fromLong(7) * UInt128.fromLong(6))
    }

    @Test fun mulByZero() {
        assertEquals(UInt128.ZERO, UInt128.fromLong(999) * UInt128.ZERO)
    }

    @Test fun mulCrossWord() {
        val a = UInt128.fromLong(1L shl 32)
        assertEquals(UInt128(1L, 0L), a * a)
    }

    @Test fun mulLargeUnsigned() {
        assertEquals(UInt128(1L, -2L), UInt128(0L, -1L) * UInt128.fromLong(2))
    }

    @Test fun divSmall() {
        assertEquals(UInt128.fromLong(7), UInt128.fromLong(42) / UInt128.fromLong(6))
    }

    @Test fun divByOne() {
        val v = UInt128(5L, 100L)
        assertEquals(v, v / UInt128.ONE)
    }

    @Test fun divByZeroThrows() {
        assertFailsWith<ArithmeticException> { UInt128.ONE / UInt128.ZERO }
    }

    @Test fun divLargeBySmall() {
        assertEquals(UInt128(0L, Long.MIN_VALUE), UInt128(1L, 0L) / UInt128.fromLong(2))
    }

    @Test fun remSmall() {
        assertEquals(UInt128.fromLong(2), UInt128.fromLong(17) % UInt128.fromLong(5))
    }

    @Test fun andOp() {
        assertEquals(UInt128(0x0FL, 0x0FL), UInt128(0xFFL, 0xFFL) and UInt128(0x0FL, 0x0FL))
    }

    @Test fun orOp() {
        assertEquals(UInt128(0xFFL, 0xFFL), UInt128(0xF0L, 0xF0L) or UInt128(0x0FL, 0x0FL))
    }

    @Test fun xorSelf() {
        assertEquals(UInt128.ZERO, UInt128(123L, 456L) xor UInt128(123L, 456L))
    }

    @Test fun shlSmall() {
        assertEquals(UInt128.fromLong(8), UInt128.ONE shl 3)
    }

    @Test fun shlCrossWord() {
        assertEquals(UInt128(1L, 0L), UInt128.ONE shl 64)
    }

    @Test fun shrLogical() {
        assertEquals(UInt128(Long.MAX_VALUE, -1L), UInt128.MAX_VALUE shr 1)
    }

    @Test fun shrCrossWord() {
        assertEquals(UInt128.ONE, UInt128(1L, 0L) shr 64)
    }

    @Test fun compareUnsigned() {
        assertTrue(UInt128(-1L, 0L) > UInt128(Long.MAX_VALUE, 0L))
    }

    @Test fun toInt128Reinterprets() {
        assertEquals(Int128.NEGATIVE_ONE, UInt128.MAX_VALUE.toInt128())
    }

    @Test fun toStringZero() {
        assertEquals("0", UInt128.ZERO.toString())
    }

    @Test fun toStringSmall() {
        assertEquals("42", UInt128.fromLong(42).toString())
    }

    @Test fun toStringPow64() {
        assertEquals("18446744073709551616", UInt128(1L, 0L).toString())
    }

    @Test fun toStringMax() {
        assertEquals("340282366920938463463374607431768211455", UInt128.MAX_VALUE.toString())
    }

    @Test fun parseRoundTrip() {
        val v = UInt128(12345L, 67890L)
        assertEquals(v, UInt128.parseString(v.toString()))
    }

    @Test fun parseMaxValue() {
        assertEquals(UInt128.MAX_VALUE, UInt128.parseString("340282366920938463463374607431768211455"))
    }

    @Test fun divRemConsistency() {
        val a = UInt128.fromLong(100)
        val b = UInt128.fromLong(7)
        assertEquals(a, (a / b) * b + (a % b))
    }

    @Test fun divRemConsistencyLarge() {
        val a = UInt128(100L, 200L)
        val b = UInt128(0L, 13L)
        assertEquals(a, (a / b) * b + (a % b))
    }

    @Test fun divRemConsistencyBothLarge() {
        val a = UInt128(1000L, 2000L)
        val b = UInt128(3L, 7L)
        assertEquals(a, (a / b) * b + (a % b))
    }

    @Test fun addExactOverflowThrows() {
        assertFailsWith<ArithmeticException> { UInt128.MAX_VALUE.addExact(UInt128.ONE) }
    }

    @Test fun subtractExactUnderflowThrows() {
        assertFailsWith<ArithmeticException> { UInt128.ZERO.subtractExact(UInt128.ONE) }
    }

    @Test fun clzZero() {
        assertEquals(128, UInt128.ZERO.countLeadingZeroBits())
    }

    @Test fun popCount() {
        assertEquals(0, UInt128.ZERO.countOneBits())
        assertEquals(128, UInt128.MAX_VALUE.countOneBits())
    }

    @Test fun ulongToUInt128() {
        assertEquals(UInt128(0L, -1L), ULong.MAX_VALUE.toUInt128())
    }

    @Test fun stringToUInt128() {
        assertEquals(UInt128.fromLong(42), "42".toUInt128())
    }

    @Test fun negativeStringThrows() {
        assertFailsWith<IllegalArgumentException> { "-1".toUInt128() }
    }
}
