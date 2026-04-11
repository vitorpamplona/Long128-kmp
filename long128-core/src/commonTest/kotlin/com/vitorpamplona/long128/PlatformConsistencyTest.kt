package com.vitorpamplona.long128

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.random.Random
import kotlin.test.*

/**
 * Verifies that platform-specific optimized paths (multiply128,
 * signedDivide128, unsignedDivide128) produce identical results
 * to a BigInteger oracle on every platform.
 *
 * This catches regressions where a platform implementation diverges
 * from the reference — e.g., wrong cinterop calling convention,
 * sign handling bug in MethodHandle dispatch, or endianness issue.
 *
 * Runs on ALL platforms (JVM, native, JS, Wasm).
 */
class PlatformConsistencyTest {

    private val TWO_64 = BigInteger.ONE.shl(64)
    private val TWO_128 = BigInteger.ONE.shl(128)

    private fun Int128.toBig(): BigInteger {
        val unsigned = BigInteger.fromULong(hi.toULong()) * TWO_64 + BigInteger.fromULong(lo.toULong())
        return if (hi < 0) unsigned - TWO_128 else unsigned
    }

    private fun UInt128.toBig(): BigInteger =
        BigInteger.fromULong(hi.toULong()) * TWO_64 + BigInteger.fromULong(lo.toULong())

    private fun BigInteger.toI128(): Int128 {
        var mod = this % TWO_128
        if (mod < BigInteger.ZERO) mod += TWO_128
        val lo = (mod % TWO_64).ulongValue(exactRequired = false).toLong()
        val hi = (mod / TWO_64).ulongValue(exactRequired = false).toLong()
        return Int128(hi, lo)
    }

    private fun BigInteger.toU128(): UInt128 {
        var mod = this % TWO_128
        if (mod < BigInteger.ZERO) mod += TWO_128
        val lo = (mod % TWO_64).ulongValue(exactRequired = false).toLong()
        val hi = (mod / TWO_64).ulongValue(exactRequired = false).toLong()
        return UInt128(hi, lo)
    }

    // Stress values that exercise word boundaries and sign handling
    private val stressValues = listOf(
        Int128(0L, 0L),
        Int128(0L, 1L),
        Int128(-1L, -1L),
        Int128(Long.MAX_VALUE, -1L),  // MAX_VALUE
        Int128(Long.MIN_VALUE, 0L),   // MIN_VALUE
        Int128(0L, -1L),              // 2^64-1 in lo
        Int128(1L, 0L),               // 2^64
        Int128(-1L, 0L),
        Int128(0L, Long.MIN_VALUE),   // 2^63 in lo
        Int128(0x7FFF_FFFF_FFFF_FFFFL, -2L),
        Int128.fromLong(Long.MAX_VALUE),
        Int128.fromLong(Long.MIN_VALUE),
        Int128.fromLong(-1),
        Int128.fromLong(42),
        Int128.fromLong(-42),
        // Near word boundaries
        Int128(0L, -0x1_0000_0000L),              // 0xFFFFFFFF00000000
        Int128(0L, 0x0000_0001_0000_0000L),
        Int128(0x0000_0001L, -1L),                  // 0xFFFFFFFFFFFFFFFF
    ) + (0 until 50).map {
        val rng = Random(it + 1000)
        Int128(rng.nextLong(), rng.nextLong())
    }

    // ----- Multiply consistency -----

    @Test
    fun multiplyMatchesBigIntegerOnAllPlatforms() {
        for (a in stressValues) {
            for (b in stressValues.take(20)) { // 68 * 20 = 1360 pairs
                val expected = (a.toBig() * b.toBig()).toI128()
                val actual = a * b
                assertEquals(expected, actual,
                    "Multiply mismatch on this platform: $a * $b")
            }
        }
    }

    @Test
    fun unsignedMultiplyMatchesBigIntegerOnAllPlatforms() {
        for (a in stressValues.map { UInt128(it.hi, it.lo) }) {
            for (b in stressValues.take(20).map { UInt128(it.hi, it.lo) }) {
                val expected = (a.toBig() * b.toBig()).toU128()
                val actual = a * b
                assertEquals(expected, actual,
                    "Unsigned multiply mismatch on this platform: $a * $b")
            }
        }
    }

    // ----- Division consistency -----

    @Test
    fun signedDivisionMatchesBigIntegerOnAllPlatforms() {
        for (a in stressValues) {
            for (b in stressValues.take(20)) {
                if (b == Int128.ZERO) continue
                if (a == Int128.MIN_VALUE && b == Int128.NEGATIVE_ONE) continue
                val expectedQ = (a.toBig() / b.toBig()).toI128()
                val actualQ = a / b
                assertEquals(expectedQ, actualQ,
                    "Signed div mismatch on this platform: $a / $b")

                // Also verify divRem identity: a == q*b + r
                val actualR = a % b
                assertEquals(a, actualQ * b + actualR,
                    "DivRem identity failed on this platform: $a / $b")
            }
        }
    }

    @Test
    fun unsignedDivisionMatchesBigIntegerOnAllPlatforms() {
        for (a in stressValues.map { UInt128(it.hi, it.lo) }) {
            for (b in stressValues.take(20).map { UInt128(it.hi, it.lo) }) {
                if (b == UInt128.ZERO) continue
                val expectedQ = (a.toBig() / b.toBig()).toU128()
                val actualQ = a / b
                assertEquals(expectedQ, actualQ,
                    "Unsigned div mismatch on this platform: $a / $b")

                val actualR = a % b
                assertEquals(a, actualQ * b + actualR,
                    "Unsigned divRem identity failed on this platform: $a / $b")
            }
        }
    }

    // ----- The critical regression guard -----

    @Test
    fun multiplyHighProducesCorrectResultOnThisPlatform() {
        // These specific values exercise the unsigned correction formula.
        // If the platform switches between signed/unsigned multiplyHigh
        // implementations incorrectly, these will fail.
        data class Case(val a: Long, val b: Long, val expectedUnsignedHigh: Long)

        val cases = listOf(
            // max × max: (2^64-1)^2 = 2^128 - 2^65 + 1 → high = 2^64-2 = 0xFFFFFFFFFFFFFFFE
            Case(-1L, -1L, -2L),
            // 2^63 × 2 = 2^64 → high = 1
            Case(Long.MIN_VALUE, 2L, 1L),
            // small × small: 2 × 3 = 6 → high = 0
            Case(2L, 3L, 0L),
            // 0 × anything = 0
            Case(0L, Long.MAX_VALUE, 0L),
            // (2^64-1) × 1 = 2^64-1 → high = 0
            Case(-1L, 1L, 0L),
            // (2^63) × (2^63) = 2^126 → high = 2^62
            Case(Long.MIN_VALUE, Long.MIN_VALUE, 1L shl 62),
        )

        for ((a, b, expected) in cases) {
            val actual = com.vitorpamplona.long128.internal.unsignedMultiplyHigh(a, b)
            assertEquals(expected, actual,
                "unsignedMultiplyHigh(${a.toULong()}, ${b.toULong()}) on this platform")
        }
    }
}
