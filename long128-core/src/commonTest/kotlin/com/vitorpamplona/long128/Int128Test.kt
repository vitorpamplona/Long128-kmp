package com.vitorpamplona.long128

import kotlin.test.*

class Int128Test {

    @Test fun zeroIsZero() {
        assertEquals(0L, Int128.ZERO.hi)
        assertEquals(0L, Int128.ZERO.lo)
    }

    @Test fun oneIsOne() {
        assertEquals(0L, Int128.ONE.hi)
        assertEquals(1L, Int128.ONE.lo)
    }

    @Test fun negativeOneIsAllBits() {
        assertEquals(-1L, Int128.NEGATIVE_ONE.hi)
        assertEquals(-1L, Int128.NEGATIVE_ONE.lo)
    }

    @Test fun maxValueBits() {
        assertEquals(Long.MAX_VALUE, Int128.MAX_VALUE.hi)
        assertEquals(-1L, Int128.MAX_VALUE.lo)
    }

    @Test fun minValueBits() {
        assertEquals(Long.MIN_VALUE, Int128.MIN_VALUE.hi)
        assertEquals(0L, Int128.MIN_VALUE.lo)
    }

    @Test fun fromLongPositive() {
        val v = Int128.fromLong(42L)
        assertEquals(0L, v.hi)
        assertEquals(42L, v.lo)
    }

    @Test fun fromLongNegative() {
        val v = Int128.fromLong(-1L)
        assertEquals(-1L, v.hi)
        assertEquals(-1L, v.lo)
    }

    @Test fun addSmall() {
        assertEquals(Int128.fromLong(30), Int128.fromLong(10) + Int128.fromLong(20))
    }

    @Test fun addWithCarry() {
        val a = Int128(0L, -1L)
        val b = Int128.ONE
        assertEquals(Int128(1L, 0L), a + b)
    }

    @Test fun addOverflowWraps() {
        assertEquals(Int128.MIN_VALUE, Int128.MAX_VALUE + Int128.ONE)
    }

    @Test fun addNegatives() {
        assertEquals(Int128.fromLong(-30), Int128.fromLong(-10) + Int128.fromLong(-20))
    }

    @Test fun subSmall() {
        assertEquals(Int128.fromLong(5), Int128.fromLong(15) - Int128.fromLong(10))
    }

    @Test fun subWithBorrow() {
        assertEquals(Int128(0L, -1L), Int128(1L, 0L) - Int128.ONE)
    }

    @Test fun subUnderflowWraps() {
        assertEquals(Int128.MAX_VALUE, Int128.MIN_VALUE - Int128.ONE)
    }

    @Test fun negateOne() {
        assertEquals(Int128.NEGATIVE_ONE, -Int128.ONE)
    }

    @Test fun negateZero() {
        assertEquals(Int128.ZERO, -Int128.ZERO)
    }

    @Test fun negateNegative() {
        assertEquals(Int128.fromLong(42), -Int128.fromLong(-42))
    }

    @Test fun negateMinValueWraps() {
        assertEquals(Int128.MIN_VALUE, -Int128.MIN_VALUE)
    }

    @Test fun doubleNegate() {
        val v = Int128(123L, 456L)
        assertEquals(v, -(-v))
    }

    @Test fun mulSmall() {
        assertEquals(Int128.fromLong(42), Int128.fromLong(7) * Int128.fromLong(6))
    }

    @Test fun mulByZero() {
        assertEquals(Int128.ZERO, Int128.fromLong(999) * Int128.ZERO)
    }

    @Test fun mulByOne() {
        val v = Int128(5L, 100L)
        assertEquals(v, v * Int128.ONE)
    }

    @Test fun mulNegative() {
        assertEquals(Int128.fromLong(-12), Int128.fromLong(-3) * Int128.fromLong(4))
    }

    @Test fun mulLargeValues() {
        assertEquals(Int128(2L, 0L), Int128(1L, 0L) * Int128.fromLong(2))
    }

    @Test fun mulCrossWord() {
        val a = Int128.fromLong(1L shl 32)
        assertEquals(Int128(1L, 0L), a * a)
    }

    @Test fun divSmall() {
        assertEquals(Int128.fromLong(7), Int128.fromLong(42) / Int128.fromLong(6))
    }

    @Test fun divNegative() {
        assertEquals(Int128.fromLong(-7), Int128.fromLong(-42) / Int128.fromLong(6))
    }

    @Test fun divNegativeByNegative() {
        assertEquals(Int128.fromLong(7), Int128.fromLong(-42) / Int128.fromLong(-6))
    }

    @Test fun divByOne() {
        val v = Int128(5L, 100L)
        assertEquals(v, v / Int128.ONE)
    }

    @Test fun divByZeroThrows() {
        assertFailsWith<ArithmeticException> { Int128.ONE / Int128.ZERO }
    }

    @Test fun divMinByNegOneThrows() {
        assertFailsWith<ArithmeticException> { Int128.MIN_VALUE / Int128.NEGATIVE_ONE }
    }

    @Test fun remSmall() {
        assertEquals(Int128.fromLong(2), Int128.fromLong(17) % Int128.fromLong(5))
    }

    @Test fun remNegativeDividend() {
        assertEquals(Int128.fromLong(-2), Int128.fromLong(-17) % Int128.fromLong(5))
    }

    @Test fun remByZeroThrows() {
        assertFailsWith<ArithmeticException> { Int128.ONE % Int128.ZERO }
    }

    @Test fun andOp() {
        assertEquals(Int128(0x0FL, 0x0FL), Int128(0xFFL, 0xFFL) and Int128(0x0FL, 0x0FL))
    }

    @Test fun orOp() {
        assertEquals(Int128(0xFFL, 0xFFL), Int128(0xF0L, 0xF0L) or Int128(0x0FL, 0x0FL))
    }

    @Test fun xorOp() {
        assertEquals(Int128.ZERO, Int128(0xFFL, 0xFFL) xor Int128(0xFFL, 0xFFL))
    }

    @Test fun invOp() {
        assertEquals(Int128.NEGATIVE_ONE, Int128.ZERO.inv())
        assertEquals(Int128.ZERO, Int128.NEGATIVE_ONE.inv())
    }

    @Test fun shlSmall() {
        assertEquals(Int128.fromLong(4), Int128.ONE shl 2)
    }

    @Test fun shlCrossWord() {
        assertEquals(Int128(1L, 0L), Int128.ONE shl 64)
    }

    @Test fun shlBy127() {
        assertEquals(Int128.MIN_VALUE, Int128.ONE shl 127)
    }

    @Test fun shrArithmetic() {
        assertEquals(Int128.NEGATIVE_ONE, Int128.NEGATIVE_ONE shr 1)
    }

    @Test fun shrPositive() {
        assertEquals(Int128.fromLong(2), Int128.fromLong(8) shr 2)
    }

    @Test fun shrCrossWord() {
        assertEquals(Int128.fromLong(1L), Int128(1L, 0L) shr 64)
    }

    @Test fun ushrNegative() {
        assertEquals(Int128.MAX_VALUE, Int128.NEGATIVE_ONE ushr 1)
    }

    @Test fun compareEqual() {
        assertEquals(0, Int128.ONE.compareTo(Int128.ONE))
    }

    @Test fun compareLessThan() {
        assertTrue(Int128.ZERO < Int128.ONE)
        assertTrue(Int128.NEGATIVE_ONE < Int128.ZERO)
        assertTrue(Int128.MIN_VALUE < Int128.MAX_VALUE)
    }

    @Test fun equality() {
        assertEquals(Int128(5L, 10L), Int128(5L, 10L))
        assertNotEquals(Int128(5L, 10L), Int128(5L, 11L))
    }

    @Test fun toLongRoundTrip() {
        assertEquals(42L, Int128.fromLong(42).toLong())
        assertEquals(-42L, Int128.fromLong(-42).toLong())
    }

    @Test fun toUInt128Reinterprets() {
        assertEquals(UInt128.MAX_VALUE, Int128.NEGATIVE_ONE.toUInt128())
    }

    @Test fun toStringZero() {
        assertEquals("0", Int128.ZERO.toString())
    }

    @Test fun toStringPositive() {
        assertEquals("42", Int128.fromLong(42).toString())
    }

    @Test fun toStringNegative() {
        assertEquals("-42", Int128.fromLong(-42).toString())
    }

    @Test fun toStringLargePositive() {
        assertEquals("18446744073709551616", Int128(1L, 0L).toString())
    }

    @Test fun toStringHex() {
        assertEquals("ff", Int128.fromLong(255).toString(16))
    }

    @Test fun parseRoundTrip() {
        val v = Int128(12345L, 67890L)
        assertEquals(v, Int128.parseString(v.toString()))
    }

    @Test fun parseNegativeRoundTrip() {
        val v = -Int128(12345L, 67890L)
        assertEquals(v, Int128.parseString(v.toString()))
    }

    @Test fun parseLargeNumber() {
        assertEquals(Int128.MAX_VALUE, Int128.parseString("170141183460469231731687303715884105727"))
    }

    @Test fun addExactOverflowThrows() {
        assertFailsWith<ArithmeticException> { Int128.MAX_VALUE.addExact(Int128.ONE) }
    }

    @Test fun clzZero() {
        assertEquals(128, Int128.ZERO.countLeadingZeroBits())
    }

    @Test fun clzOne() {
        assertEquals(127, Int128.ONE.countLeadingZeroBits())
    }

    @Test fun popCount() {
        assertEquals(0, Int128.ZERO.countOneBits())
        assertEquals(128, Int128.NEGATIVE_ONE.countOneBits())
    }

    @Test fun divRemConsistency() {
        val a = Int128.fromLong(100)
        val b = Int128.fromLong(7)
        assertEquals(a, (a / b) * b + (a % b))
    }

    @Test fun divRemConsistencyLarge() {
        val a = Int128(100L, 200L)
        val b = Int128(0L, 13L)
        assertEquals(a, (a / b) * b + (a % b))
    }

    @Test fun divRemNegativeConsistency() {
        val a = Int128.fromLong(-100)
        val b = Int128.fromLong(7)
        assertEquals(a, (a / b) * b + (a % b))
    }

    @Test fun intToInt128() {
        assertEquals(Int128.fromLong(42), 42.toInt128())
    }

    @Test fun stringToInt128() {
        assertEquals(Int128.fromLong(42), "42".toInt128())
    }
}
