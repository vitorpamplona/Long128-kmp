package com.vitorpamplona.long128

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.Sign
import kotlin.random.Random
import kotlin.test.*

/**
 * Cross-platform oracle test: verifies every Int128 and UInt128 arithmetic
 * operation against com.ionspin.kotlin:bignum BigInteger on ALL targets
 * (JVM, native, JS, Wasm).
 */
class CrossPlatformOracleTest {

    private val TWO_64 = BigInteger.ONE.shl(64)
    private val TWO_128 = BigInteger.ONE.shl(128)

    // -- Conversions --

    /** Convert Int128 to BigInteger (signed). */
    private fun Int128.toBig(): BigInteger {
        val unsigned = BigInteger.fromULong(hi.toULong()) * TWO_64 + BigInteger.fromULong(lo.toULong())
        return if (hi < 0) unsigned - TWO_128 else unsigned
    }

    /** Convert UInt128 to BigInteger (unsigned, always non-negative). */
    private fun UInt128.toBig(): BigInteger =
        BigInteger.fromULong(hi.toULong()) * TWO_64 + BigInteger.fromULong(lo.toULong())

    /**
     * Truncate BigInteger to signed 128-bit two's complement.
     * Ionspin BigInteger is sign-magnitude, so we can't use bitwise AND
     * for truncation. Instead, use modular arithmetic.
     */
    private fun BigInteger.toI128(): Int128 {
        // Reduce to [0, 2^128) range
        var mod = this % TWO_128
        if (mod < BigInteger.ZERO) mod += TWO_128

        val lo = (mod % TWO_64).ulongValue(exactRequired = false).toLong()
        val hi = (mod / TWO_64).ulongValue(exactRequired = false).toLong()
        return Int128(hi, lo)
    }

    /** Truncate BigInteger to unsigned 128-bit. */
    private fun BigInteger.toU128(): UInt128 {
        var mod = this % TWO_128
        if (mod < BigInteger.ZERO) mod += TWO_128

        val lo = (mod % TWO_64).ulongValue(exactRequired = false).toLong()
        val hi = (mod / TWO_64).ulongValue(exactRequired = false).toLong()
        return UInt128(hi, lo)
    }

    // -- Test data --

    private val edgeCases = listOf(
        Int128.ZERO,
        Int128.ONE,
        Int128.NEGATIVE_ONE,
        Int128.MAX_VALUE,
        Int128.MIN_VALUE,
        Int128(0L, -1L),                       // 2^64 - 1
        Int128(1L, 0L),                         // 2^64
        Int128(-1L, 0L),
        Int128(0L, Long.MAX_VALUE),             // 2^63 - 1
        Int128(0L, Long.MIN_VALUE),             // 2^63 as unsigned lo
        Int128(Long.MAX_VALUE, 0L),
        Int128.fromLong(42),
        Int128.fromLong(-42),
        Int128.fromLong(Long.MAX_VALUE),
        Int128.fromLong(Long.MIN_VALUE),
        Int128(0x0000_0000_FFFF_FFFFL, -1L),
        Int128(0L, 0x0000_0001_0000_0000L),     // 2^32
        Int128(1L, 1L),
        Int128(-2L, 0L),
        Int128(0x7FFF_FFFF_FFFF_FFFFL, 0x7FFF_FFFF_FFFF_FFFFL),
    )

    private fun randoms(count: Int, seed: Int = 42): List<Int128> {
        val rng = Random(seed)
        return (0 until count).map { Int128(rng.nextLong(), rng.nextLong()) }
    }

    private fun pairs(): List<Pair<Int128, Int128>> {
        val ep = edgeCases.flatMap { a -> edgeCases.map { b -> a to b } }
        val rp = randoms(200).chunked(2).map { it[0] to it[1] }
        return ep + rp
    }

    private fun uEdge(): List<UInt128> = edgeCases.map { UInt128(it.hi, it.lo) }
    private fun uRandoms(count: Int, seed: Int = 99) = randoms(count, seed).map { UInt128(it.hi, it.lo) }
    private fun uPairs(): List<Pair<UInt128, UInt128>> {
        val ep = uEdge().flatMap { a -> uEdge().map { b -> a to b } }
        val rp = uRandoms(200).chunked(2).map { it[0] to it[1] }
        return ep + rp
    }

    // =================================================================
    // Int128 operations
    // =================================================================

    @Test fun int128Add() {
        for ((a, b) in pairs()) {
            val expected = (a.toBig() + b.toBig()).toI128()
            assertEquals(expected, a + b, "$a + $b")
        }
    }

    @Test fun int128Sub() {
        for ((a, b) in pairs()) {
            val expected = (a.toBig() - b.toBig()).toI128()
            assertEquals(expected, a - b, "$a - $b")
        }
    }

    @Test fun int128Mul() {
        for ((a, b) in pairs()) {
            val expected = (a.toBig() * b.toBig()).toI128()
            assertEquals(expected, a * b, "$a * $b")
        }
    }

    @Test fun int128Div() {
        for ((a, b) in pairs()) {
            if (b == Int128.ZERO) continue
            if (a == Int128.MIN_VALUE && b == Int128.NEGATIVE_ONE) continue
            val expected = (a.toBig() / b.toBig()).toI128()
            assertEquals(expected, a / b, "$a / $b")
        }
    }

    @Test fun int128Rem() {
        // Verify remainder via the identity: a == (a/b)*b + (a%b)
        // This avoids ionspin's % semantics differences (floored vs truncated).
        for ((a, b) in pairs()) {
            if (b == Int128.ZERO) continue
            if (a == Int128.MIN_VALUE && b == Int128.NEGATIVE_ONE) continue
            val q = a / b
            val r = a % b
            assertEquals(a, q * b + r, "$a == ($a/$b)*$b + ($a%$b)")
            // Also verify division quotient against BigInteger
            val expectedQ = (a.toBig() / b.toBig()).toI128()
            assertEquals(expectedQ, q, "$a / $b")
        }
    }

    @Test fun int128Negate() {
        for (v in edgeCases + randoms(200)) {
            val expected = v.toBig().negate().toI128()
            assertEquals(expected, -v, "-($v)")
        }
    }

    @Test fun int128Shl() {
        val shifts = listOf(0, 1, 2, 31, 32, 33, 63, 64, 65, 96, 127)
        for (v in edgeCases + randoms(50)) {
            for (n in shifts) {
                val expected = v.toBig().shl(n).toI128()
                assertEquals(expected, v shl n, "$v shl $n")
            }
        }
    }

    @Test fun int128Shr() {
        // Ionspin BigInteger.shr on negative values rounds toward zero (like unsigned shift),
        // not toward negative infinity (like Java/Kotlin arithmetic shr).
        // So for negative values we compute: floor(v / 2^n) using BigInteger division instead.
        val shifts = listOf(0, 1, 2, 31, 32, 33, 63, 64, 65, 96, 127)
        for (v in edgeCases + randoms(50)) {
            for (n in shifts) {
                val big = v.toBig()
                // Arithmetic right shift = floor division by 2^n
                val divisor = BigInteger.ONE.shl(n)
                val expected = if (big >= BigInteger.ZERO) {
                    (big / divisor).toI128()
                } else {
                    // Floor division for negative: -((-v - 1) / 2^n) - 1
                    val neg = big.negate() - BigInteger.ONE
                    val shifted = neg / divisor
                    (shifted.negate() - BigInteger.ONE).toI128()
                }
                assertEquals(expected, v shr n, "$v shr $n")
            }
        }
    }

    @Test fun int128And() {
        // Bitwise ops: verify by converting to unsigned 128-bit, doing bitwise, converting back.
        // Ionspin BigInteger doesn't support two's-complement bitwise on negatives,
        // so we test bitwise correctness at the Long level directly.
        for ((a, b) in pairs()) {
            val expected = Int128(a.hi and b.hi, a.lo and b.lo)
            assertEquals(expected, a and b, "$a and $b")
        }
    }

    @Test fun int128Or() {
        for ((a, b) in pairs()) {
            val expected = Int128(a.hi or b.hi, a.lo or b.lo)
            assertEquals(expected, a or b, "$a or $b")
        }
    }

    @Test fun int128Xor() {
        for ((a, b) in pairs()) {
            val expected = Int128(a.hi xor b.hi, a.lo xor b.lo)
            assertEquals(expected, a xor b, "$a xor $b")
        }
    }

    @Test fun int128CompareTo() {
        for ((a, b) in pairs()) {
            val expected = a.toBig().compareTo(b.toBig()).coerceIn(-1, 1)
            val actual = a.compareTo(b).coerceIn(-1, 1)
            assertEquals(expected, actual, "compareTo: $a vs $b")
        }
    }

    @Test fun int128ToString() {
        for (v in edgeCases + randoms(100)) {
            val expected = v.toBig().toString()
            assertEquals(expected, v.toString(), "toString: hi=${v.hi}, lo=${v.lo}")
        }
    }

    @Test fun int128ParseRoundTrip() {
        // Verify toString → parseString round-trips for all edge cases and randoms
        for (v in edgeCases + randoms(100)) {
            val s = v.toString()
            val parsed = Int128.parseString(s)
            assertEquals(v, parsed, "parse(toString($v)) should round-trip")
        }
    }

    @Test fun int128DivRemConsistency() {
        for ((a, b) in pairs()) {
            if (b == Int128.ZERO) continue
            if (a == Int128.MIN_VALUE && b == Int128.NEGATIVE_ONE) continue
            val q = a / b; val r = a % b
            assertEquals(a, q * b + r, "$a == ($a/$b)*$b + ($a%$b)")
        }
    }

    // =================================================================
    // UInt128 operations
    // =================================================================

    @Test fun uint128Add() {
        for ((a, b) in uPairs()) {
            val expected = (a.toBig() + b.toBig()).toU128()
            assertEquals(expected, a + b, "$a + $b")
        }
    }

    @Test fun uint128Sub() {
        for ((a, b) in uPairs()) {
            val expected = (a.toBig() - b.toBig() + TWO_128).toU128()
            assertEquals(expected, a - b, "$a - $b")
        }
    }

    @Test fun uint128Mul() {
        for ((a, b) in uPairs()) {
            val expected = (a.toBig() * b.toBig()).toU128()
            assertEquals(expected, a * b, "$a * $b")
        }
    }

    @Test fun uint128Div() {
        for ((a, b) in uPairs()) {
            if (b == UInt128.ZERO) continue
            val expected = (a.toBig() / b.toBig()).toU128()
            assertEquals(expected, a / b, "$a / $b")
        }
    }

    @Test fun uint128Rem() {
        for ((a, b) in uPairs()) {
            if (b == UInt128.ZERO) continue
            val expected = (a.toBig() % b.toBig()).toU128()
            assertEquals(expected, a % b, "$a % $b")
        }
    }

    @Test fun uint128CompareTo() {
        for ((a, b) in uPairs()) {
            val expected = a.toBig().compareTo(b.toBig()).coerceIn(-1, 1)
            val actual = a.compareTo(b).coerceIn(-1, 1)
            assertEquals(expected, actual, "compareTo: $a vs $b")
        }
    }

    @Test fun uint128ToString() {
        for (v in uEdge() + uRandoms(100)) {
            val expected = v.toBig().toString()
            assertEquals(expected, v.toString(), "toString: hi=${v.hi}, lo=${v.lo}")
        }
    }

    @Test fun uint128Shl() {
        val shifts = listOf(0, 1, 32, 63, 64, 65, 127)
        for (v in uEdge() + uRandoms(50)) {
            for (n in shifts) {
                val expected = v.toBig().shl(n).toU128()
                assertEquals(expected, v shl n, "$v shl $n")
            }
        }
    }

    @Test fun uint128Shr() {
        val shifts = listOf(0, 1, 32, 63, 64, 65, 127)
        for (v in uEdge() + uRandoms(50)) {
            for (n in shifts) {
                val expected = v.toBig().shr(n).toU128()
                assertEquals(expected, v shr n, "$v shr $n")
            }
        }
    }

    @Test fun uint128DivRemConsistency() {
        for ((a, b) in uPairs()) {
            if (b == UInt128.ZERO) continue
            val q = a / b; val r = a % b
            assertEquals(a, q * b + r, "$a == ($a/$b)*$b + ($a%$b)")
        }
    }
}
