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

    // ── Conversions (narrowing) ─────────────────────────────────────

    @Test fun toByteNarrows() {
        assertEquals(0x7F.toByte(), Int128.fromLong(0x7F).toByte())
        assertEquals((-1).toByte(), Int128.NEGATIVE_ONE.toByte())
        // Truncates: only low 8 bits
        assertEquals(0x10.toByte(), Int128.fromLong(0x110).toByte())
    }

    @Test fun toShortNarrows() {
        assertEquals(0x7FFF.toShort(), Int128.fromLong(0x7FFF).toShort())
        assertEquals((-1).toShort(), Int128.NEGATIVE_ONE.toShort())
    }

    @Test fun toIntNarrowsLargeValues() {
        assertEquals(0, Int128(1L, 0L).toInt())  // 2^64 truncates to 0
        assertEquals(-1, Int128.NEGATIVE_ONE.toInt())
    }

    @Test fun toDoubleSmallValues() {
        assertEquals(0.0, Int128.ZERO.toDouble())
        assertEquals(42.0, Int128.fromLong(42).toDouble())
        assertEquals(-42.0, Int128.fromLong(-42).toDouble())
        assertEquals(Long.MAX_VALUE.toDouble(), Int128.fromLong(Long.MAX_VALUE).toDouble())
        assertEquals(Long.MIN_VALUE.toDouble(), Int128.fromLong(Long.MIN_VALUE).toDouble())
    }

    @Test fun toDoubleLargeValues() {
        // 2^64 = 18446744073709551616.0
        assertEquals(18446744073709551616.0, Int128(1L, 0L).toDouble(), 1.0)
        // Negative large
        val neg = -Int128(1L, 0L)
        assertEquals(-18446744073709551616.0, neg.toDouble(), 1.0)
    }

    @Test fun toFloatSmall() {
        assertEquals(42.0f, Int128.fromLong(42).toFloat())
    }

    // ── inc / dec ───────────────────────────────────────────────────

    @Test fun incFromZero() {
        var v = Int128.ZERO
        v++
        assertEquals(Int128.ONE, v)
    }

    @Test fun decFromOne() {
        var v = Int128.ONE
        v--
        assertEquals(Int128.ZERO, v)
    }

    @Test fun incMaxValueWraps() {
        var v = Int128.MAX_VALUE
        v++
        assertEquals(Int128.MIN_VALUE, v)
    }

    @Test fun decMinValueWraps() {
        var v = Int128.MIN_VALUE
        v--
        assertEquals(Int128.MAX_VALUE, v)
    }

    // ── unaryPlus / isNegative ──────────────────────────────────────

    @Test fun unaryPlusReturnsSelf() {
        val v = Int128(123L, 456L)
        assertEquals(v, +v)
    }

    @Test fun isNegativeCorrect() {
        assertTrue(Int128.MIN_VALUE.isNegative())
        assertTrue(Int128.NEGATIVE_ONE.isNegative())
        assertFalse(Int128.ZERO.isNegative())
        assertFalse(Int128.ONE.isNegative())
        assertFalse(Int128.MAX_VALUE.isNegative())
    }

    // ── countTrailingZeroBits ───────────────────────────────────────

    @Test fun ctzZero() {
        assertEquals(128, Int128.ZERO.countTrailingZeroBits())
    }

    @Test fun ctzOne() {
        assertEquals(0, Int128.ONE.countTrailingZeroBits())
    }

    @Test fun ctzPowerOfTwo() {
        assertEquals(64, Int128(1L, 0L).countTrailingZeroBits())  // 2^64
        assertEquals(127, Int128.MIN_VALUE.countTrailingZeroBits())  // 2^127
    }

    // ── hashCode contract ───────────────────────────────────────────

    @Test fun equalObjectsHaveEqualHashCodes() {
        val a = Int128(12345L, 67890L)
        val b = Int128(12345L, 67890L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun differentObjectsLikelyDifferentHashCodes() {
        val hashes = setOf(
            Int128.ZERO.hashCode(), Int128.ONE.hashCode(),
            Int128.MAX_VALUE.hashCode(), Int128.MIN_VALUE.hashCode(),
            Int128.NEGATIVE_ONE.hashCode(),
            Int128(12345L, 67890L).hashCode()
        )
        assertTrue(hashes.size >= 3, "Hash codes should vary across distinct values, got $hashes")
    }

    // ── toString all radixes ────────────────────────────────────────

    @Test fun toStringAllRadixes() {
        val v = Int128.fromLong(255)
        assertEquals("11111111", v.toString(2))
        assertEquals("377", v.toString(8))
        assertEquals("255", v.toString(10))
        assertEquals("ff", v.toString(16))
        assertEquals("73", v.toString(36))
    }

    @Test fun toStringRadixBounds() {
        assertFailsWith<IllegalArgumentException> { Int128.ONE.toString(1) }
        assertFailsWith<IllegalArgumentException> { Int128.ONE.toString(37) }
    }

    // ── parseString error handling ──────────────────────────────────

    @Test fun parseEmptyStringFails() {
        assertFailsWith<IllegalArgumentException> { Int128.parseString("") }
    }

    @Test fun parseJustSignFails() {
        assertFailsWith<IllegalArgumentException> { Int128.parseString("+") }
        assertFailsWith<IllegalArgumentException> { Int128.parseString("-") }
    }

    @Test fun parseInvalidCharFails() {
        assertFailsWith<NumberFormatException> { Int128.parseString("12x4") }
    }

    @Test fun parseOrNullReturnsNull() {
        assertNull(Int128.parseStringOrNull(""))
        assertNull(Int128.parseStringOrNull("abc"))
        assertNull("not_a_number".toInt128OrNull())
    }

    @Test fun parseLeadingZeros() {
        assertEquals(Int128.fromLong(7), Int128.parseString("007"))
    }

    @Test fun parseWithPlusSign() {
        assertEquals(Int128.fromLong(42), Int128.parseString("+42"))
    }

    // ── sign property ───────────────────────────────────────────────

    @Test fun signProperty() {
        assertEquals(-1, Int128.MIN_VALUE.sign)
        assertEquals(-1, Int128.NEGATIVE_ONE.sign)
        assertEquals(0, Int128.ZERO.sign)
        assertEquals(1, Int128.ONE.sign)
        assertEquals(1, Int128.MAX_VALUE.sign)
    }
}
