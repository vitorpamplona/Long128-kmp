package com.vitorpamplona.long128

import java.math.BigInteger
import kotlin.random.Random
import kotlin.test.*

/**
 * Verifies every Int128 and UInt128 arithmetic operation against
 * java.math.BigInteger as the ground-truth oracle.
 *
 * Strategy: for each operation, test a curated set of edge-case values
 * plus random values, compute with both Int128 and BigInteger, and assert
 * identical results.
 */
class BigIntegerOracleTest {

    // -- Helpers: Int128 ↔ BigInteger conversion --

    private val TWO_128 = BigInteger.ONE.shiftLeft(128)
    private val TWO_127 = BigInteger.ONE.shiftLeft(127)
    private val MASK_128 = TWO_128 - BigInteger.ONE

    /** Convert Int128 to BigInteger (signed, two's complement). */
    private fun Int128.toBigInteger(): BigInteger {
        val unsigned = BigInteger(1, byteArrayOf(
            (hi ushr 56).toByte(), (hi ushr 48).toByte(), (hi ushr 40).toByte(), (hi ushr 32).toByte(),
            (hi ushr 24).toByte(), (hi ushr 16).toByte(), (hi ushr 8).toByte(), hi.toByte(),
            (lo ushr 56).toByte(), (lo ushr 48).toByte(), (lo ushr 40).toByte(), (lo ushr 32).toByte(),
            (lo ushr 24).toByte(), (lo ushr 16).toByte(), (lo ushr 8).toByte(), lo.toByte(),
        ))
        return if (hi < 0) unsigned - TWO_128 else unsigned
    }

    /** Convert UInt128 to BigInteger (unsigned, always non-negative). */
    private fun UInt128.toBigInteger(): BigInteger {
        return BigInteger(1, byteArrayOf(
            (hi ushr 56).toByte(), (hi ushr 48).toByte(), (hi ushr 40).toByte(), (hi ushr 32).toByte(),
            (hi ushr 24).toByte(), (hi ushr 16).toByte(), (hi ushr 8).toByte(), hi.toByte(),
            (lo ushr 56).toByte(), (lo ushr 48).toByte(), (lo ushr 40).toByte(), (lo ushr 32).toByte(),
            (lo ushr 24).toByte(), (lo ushr 16).toByte(), (lo ushr 8).toByte(), lo.toByte(),
        ))
    }

    /** Truncate a BigInteger to 128-bit signed two's complement. */
    private fun BigInteger.toInt128Truncated(): Int128 {
        val masked = this.and(MASK_128)
        val bytes = masked.toByteArray()
        var hi = 0L; var lo = 0L
        // Build from big-endian bytes
        for (i in bytes.indices) {
            val byteIndex = bytes.size - 1 - i
            if (i < 8) lo = lo or ((bytes[byteIndex].toLong() and 0xFF) shl (i * 8))
            else if (i < 16) hi = hi or ((bytes[byteIndex].toLong() and 0xFF) shl ((i - 8) * 8))
        }
        return Int128(hi, lo)
    }

    /** Truncate a BigInteger to 128-bit unsigned. */
    private fun BigInteger.toUInt128Truncated(): UInt128 {
        val masked = this.and(MASK_128)
        val bytes = masked.toByteArray()
        var hi = 0L; var lo = 0L
        for (i in bytes.indices) {
            val byteIndex = bytes.size - 1 - i
            if (i < 8) lo = lo or ((bytes[byteIndex].toLong() and 0xFF) shl (i * 8))
            else if (i < 16) hi = hi or ((bytes[byteIndex].toLong() and 0xFF) shl ((i - 8) * 8))
        }
        return UInt128(hi, lo)
    }

    // -- Test value generators --

    /** Edge-case Int128 values that stress word boundaries and sign. */
    private val int128EdgeCases = listOf(
        Int128.ZERO,
        Int128.ONE,
        Int128.NEGATIVE_ONE,
        Int128.MAX_VALUE,
        Int128.MIN_VALUE,
        Int128(0L, -1L),                       // 2^64 - 1  (low word all 1s)
        Int128(1L, 0L),                         // 2^64      (carry boundary)
        Int128(-1L, 0L),                        // high word all 1s, low = 0
        Int128(0L, Long.MAX_VALUE),             // 2^63 - 1
        Int128(0L, Long.MIN_VALUE),             // 2^63 (unsigned)
        Int128(Long.MAX_VALUE, 0L),             // near MAX_VALUE with low=0
        Int128(Long.MIN_VALUE, -1L),            // MIN_VALUE + (2^64-1) = -1 - 2^64
        Int128.fromLong(42),
        Int128.fromLong(-42),
        Int128.fromLong(Long.MAX_VALUE),
        Int128.fromLong(Long.MIN_VALUE),
        Int128(0x0000_0000_FFFF_FFFFL, -1L),   // 32-bit boundary in hi
        Int128(0L, 0x0000_0001_0000_0000L),     // 2^32 in lo
        Int128(1L, 1L),                         // small with hi=1
        Int128(-2L, 0L),                        // -2 * 2^64
    )

    /** Generate random Int128 values. */
    private fun randomInt128s(count: Int, seed: Int = 42): List<Int128> {
        val rng = Random(seed)
        return (0 until count).map { Int128(rng.nextLong(), rng.nextLong()) }
    }

    /** All test pairs: edge × edge + random × random. */
    private fun testPairs(): List<Pair<Int128, Int128>> {
        val edge = int128EdgeCases.flatMap { a -> int128EdgeCases.map { b -> a to b } }
        val random = randomInt128s(200).chunked(2).map { it[0] to it[1] }
        return edge + random
    }

    private fun uint128EdgeCases(): List<UInt128> = int128EdgeCases.map { UInt128(it.hi, it.lo) }

    private fun randomUInt128s(count: Int, seed: Int = 99): List<UInt128> {
        val rng = Random(seed)
        return (0 until count).map { UInt128(rng.nextLong(), rng.nextLong()) }
    }

    private fun uint128TestPairs(): List<Pair<UInt128, UInt128>> {
        val edge = uint128EdgeCases().flatMap { a -> uint128EdgeCases().map { b -> a to b } }
        val random = randomUInt128s(200).chunked(2).map { it[0] to it[1] }
        return edge + random
    }

    // ===================================================================
    // Int128 TESTS — every operation verified against BigInteger
    // ===================================================================

    @Test
    fun int128AdditionMatchesBigInteger() {
        for ((a, b) in testPairs()) {
            val expected = (a.toBigInteger() + b.toBigInteger()).toInt128Truncated()
            val actual = a + b
            assertEquals(expected, actual, "$a + $b")
        }
    }

    @Test
    fun int128SubtractionMatchesBigInteger() {
        for ((a, b) in testPairs()) {
            val expected = (a.toBigInteger() - b.toBigInteger()).toInt128Truncated()
            val actual = a - b
            assertEquals(expected, actual, "$a - $b")
        }
    }

    @Test
    fun int128MultiplicationMatchesBigInteger() {
        for ((a, b) in testPairs()) {
            val expected = (a.toBigInteger() * b.toBigInteger()).toInt128Truncated()
            val actual = a * b
            assertEquals(expected, actual, "$a * $b")
        }
    }

    @Test
    fun int128DivisionMatchesBigInteger() {
        for ((a, b) in testPairs()) {
            if (b == Int128.ZERO) continue
            if (a == Int128.MIN_VALUE && b == Int128.NEGATIVE_ONE) continue // overflow case
            val expected = (a.toBigInteger() / b.toBigInteger()).toInt128Truncated()
            val actual = a / b
            assertEquals(expected, actual, "$a / $b")
        }
    }

    @Test
    fun int128RemainderMatchesBigInteger() {
        for ((a, b) in testPairs()) {
            if (b == Int128.ZERO) continue
            if (a == Int128.MIN_VALUE && b == Int128.NEGATIVE_ONE) continue
            val expected = (a.toBigInteger().rem(b.toBigInteger())).toInt128Truncated()
            val actual = a % b
            assertEquals(expected, actual, "$a % $b")
        }
    }

    @Test
    fun int128NegationMatchesBigInteger() {
        for (v in int128EdgeCases + randomInt128s(200)) {
            val expected = v.toBigInteger().negate().toInt128Truncated()
            val actual = -v
            assertEquals(expected, actual, "-($v)")
        }
    }

    @Test
    fun int128ShiftLeftMatchesBigInteger() {
        val values = int128EdgeCases + randomInt128s(50)
        val shifts = listOf(0, 1, 2, 31, 32, 33, 63, 64, 65, 96, 127)
        for (v in values) {
            for (n in shifts) {
                val expected = (v.toBigInteger().shiftLeft(n)).toInt128Truncated()
                val actual = v shl n
                assertEquals(expected, actual, "$v shl $n")
            }
        }
    }

    @Test
    fun int128ShiftRightMatchesBigInteger() {
        val values = int128EdgeCases + randomInt128s(50)
        val shifts = listOf(0, 1, 2, 31, 32, 33, 63, 64, 65, 96, 127)
        for (v in values) {
            for (n in shifts) {
                // BigInteger.shiftRight on a negative number is arithmetic (sign-extending)
                val expected = (v.toBigInteger().shiftRight(n)).toInt128Truncated()
                val actual = v shr n
                assertEquals(expected, actual, "$v shr $n")
            }
        }
    }

    @Test
    fun int128BitwiseAndMatchesBigInteger() {
        for ((a, b) in testPairs()) {
            val expected = (a.toBigInteger().and(b.toBigInteger())).toInt128Truncated()
            val actual = a and b
            assertEquals(expected, actual, "$a and $b")
        }
    }

    @Test
    fun int128BitwiseOrMatchesBigInteger() {
        for ((a, b) in testPairs()) {
            val expected = (a.toBigInteger().or(b.toBigInteger())).toInt128Truncated()
            val actual = a or b
            assertEquals(expected, actual, "$a or $b")
        }
    }

    @Test
    fun int128BitwiseXorMatchesBigInteger() {
        for ((a, b) in testPairs()) {
            val expected = (a.toBigInteger().xor(b.toBigInteger())).toInt128Truncated()
            val actual = a xor b
            assertEquals(expected, actual, "$a xor $b")
        }
    }

    @Test
    fun int128ComparisonMatchesBigInteger() {
        for ((a, b) in testPairs()) {
            val expected = a.toBigInteger().compareTo(b.toBigInteger())
            val actual = a.compareTo(b)
            assertEquals(expected.sign(), actual.sign(), "compareTo: $a vs $b")
        }
    }

    @Test
    fun int128ToStringMatchesBigInteger() {
        for (v in int128EdgeCases + randomInt128s(100)) {
            val expected = v.toBigInteger().toString()
            val actual = v.toString()
            assertEquals(expected, actual, "toString of $v (hi=${v.hi}, lo=${v.lo})")
        }
    }

    @Test
    fun int128ToStringRadix16MatchesBigInteger() {
        for (v in int128EdgeCases + randomInt128s(50)) {
            val expected = v.toBigInteger().toString(16)
            val actual = v.toString(16)
            assertEquals(expected, actual, "toString(16) of $v")
        }
    }

    @Test
    fun int128DivRemConsistencyWithBigInteger() {
        for ((a, b) in testPairs()) {
            if (b == Int128.ZERO) continue
            if (a == Int128.MIN_VALUE && b == Int128.NEGATIVE_ONE) continue
            val q = a / b
            val r = a % b
            assertEquals(a, q * b + r, "divRem consistency: $a = ($a / $b) * $b + ($a % $b)")
        }
    }

    // ===================================================================
    // UInt128 TESTS — every operation verified against BigInteger
    // ===================================================================

    @Test
    fun uint128AdditionMatchesBigInteger() {
        for ((a, b) in uint128TestPairs()) {
            val expected = (a.toBigInteger() + b.toBigInteger()).toUInt128Truncated()
            val actual = a + b
            assertEquals(expected, actual, "$a + $b")
        }
    }

    @Test
    fun uint128SubtractionMatchesBigInteger() {
        for ((a, b) in uint128TestPairs()) {
            val expected = (a.toBigInteger() - b.toBigInteger() + TWO_128).toUInt128Truncated()
            val actual = a - b
            assertEquals(expected, actual, "$a - $b")
        }
    }

    @Test
    fun uint128MultiplicationMatchesBigInteger() {
        for ((a, b) in uint128TestPairs()) {
            val expected = (a.toBigInteger() * b.toBigInteger()).toUInt128Truncated()
            val actual = a * b
            assertEquals(expected, actual, "$a * $b")
        }
    }

    @Test
    fun uint128DivisionMatchesBigInteger() {
        for ((a, b) in uint128TestPairs()) {
            if (b == UInt128.ZERO) continue
            val expected = (a.toBigInteger() / b.toBigInteger()).toUInt128Truncated()
            val actual = a / b
            assertEquals(expected, actual, "$a / $b")
        }
    }

    @Test
    fun uint128RemainderMatchesBigInteger() {
        for ((a, b) in uint128TestPairs()) {
            if (b == UInt128.ZERO) continue
            val expected = (a.toBigInteger().rem(b.toBigInteger())).toUInt128Truncated()
            val actual = a % b
            assertEquals(expected, actual, "$a % $b")
        }
    }

    @Test
    fun uint128ComparisonMatchesBigInteger() {
        for ((a, b) in uint128TestPairs()) {
            val expected = a.toBigInteger().compareTo(b.toBigInteger())
            val actual = a.compareTo(b)
            assertEquals(expected.sign(), actual.sign(), "compareTo: $a vs $b")
        }
    }

    @Test
    fun uint128ToStringMatchesBigInteger() {
        for (v in uint128EdgeCases() + randomUInt128s(100)) {
            val expected = v.toBigInteger().toString()
            val actual = v.toString()
            assertEquals(expected, actual, "toString of UInt128(hi=${v.hi}, lo=${v.lo})")
        }
    }

    @Test
    fun uint128DivRemConsistencyWithBigInteger() {
        for ((a, b) in uint128TestPairs()) {
            if (b == UInt128.ZERO) continue
            val q = a / b
            val r = a % b
            assertEquals(a, q * b + r, "divRem consistency: $a = ($a / $b) * $b + ($a % $b)")
        }
    }

    @Test
    fun uint128ShiftLeftMatchesBigInteger() {
        val values = uint128EdgeCases() + randomUInt128s(50)
        val shifts = listOf(0, 1, 32, 63, 64, 65, 127)
        for (v in values) {
            for (n in shifts) {
                val expected = (v.toBigInteger().shiftLeft(n)).toUInt128Truncated()
                val actual = v shl n
                assertEquals(expected, actual, "$v shl $n")
            }
        }
    }

    @Test
    fun uint128ShiftRightMatchesBigInteger() {
        val values = uint128EdgeCases() + randomUInt128s(50)
        val shifts = listOf(0, 1, 32, 63, 64, 65, 127)
        for (v in values) {
            for (n in shifts) {
                // Unsigned: BigInteger is always non-negative so shiftRight is logical
                val expected = (v.toBigInteger().shiftRight(n)).toUInt128Truncated()
                val actual = v shr n
                assertEquals(expected, actual, "$v shr $n")
            }
        }
    }

    // -- Utility --

    private fun Int.sign(): Int = when {
        this < 0 -> -1
        this > 0 -> 1
        else -> 0
    }
}
