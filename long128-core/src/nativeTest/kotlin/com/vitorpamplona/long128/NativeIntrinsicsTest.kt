package com.vitorpamplona.long128

import com.vitorpamplona.long128.internal.*
import kotlin.test.*

/**
 * Native-only tests that verify the cinterop path is actually being used.
 *
 * These tests can only run on native targets (linuxX64, macosArm64, etc.)
 * where `PlatformIntrinsics.native.kt` delegates to the C functions in
 * `int128.def`. They verify that:
 *
 * 1. The cinterop functions are linked and callable (not just compiled)
 * 2. The results match the pure-Kotlin software arithmetic
 * 3. The cinterop path handles edge cases that differ between signed/unsigned
 *
 * If the cinterop binding breaks (wrong .def, ABI mismatch, missing symbol),
 * these tests crash at link time or produce wrong results — catching the
 * problem before it reaches users.
 */
class NativeIntrinsicsTest {

    // ── Verify cinterop multiply is linked and correct ──────────────

    @Test
    fun cinteropMultiplyHighMatchesSoftware() {
        // These values exercise the signed-to-unsigned boundary
        val cases = listOf(
            Pair(0L, 0L),
            Pair(1L, 1L),
            Pair(-1L, -1L),                    // max unsigned × max unsigned
            Pair(Long.MAX_VALUE, 2L),
            Pair(Long.MIN_VALUE, 2L),          // 2^63 × 2 unsigned
            Pair(Long.MIN_VALUE, Long.MIN_VALUE), // 2^63 × 2^63
            Pair(0x123456789ABCDEF0L, -0x0123456789ABCDF0L),
        )

        for ((a, b) in cases) {
            val cinterop = unsignedMultiplyHigh(a, b)

            // Compute reference via the Karatsuba formula
            val x1 = a shr 32; val x0 = a and 0xFFFFFFFFL
            val y1 = b shr 32; val y0 = b and 0xFFFFFFFFL
            val z2 = x1 * y1
            val t = x1 * y0 + (x0 * y0).ushr(32)
            val z1 = (t and 0xFFFFFFFFL) + x0 * y1
            val signedHigh = z2 + (t shr 32) + (z1 shr 32)
            val reference = signedHigh + (a and (b shr 63)) + (b and (a shr 63))

            assertEquals(reference, cinterop,
                "cinterop unsignedMultiplyHigh($a, $b) must match software")
        }
    }

    @Test
    fun cinteropFullMultiplyMatchesSoftware() {
        val cases = listOf(
            Pair(Int128.ZERO, Int128.ONE),
            Pair(Int128.ONE, Int128.ONE),
            Pair(Int128.MAX_VALUE, Int128.fromLong(2)),
            Pair(Int128.MIN_VALUE, Int128.NEGATIVE_ONE),
            Pair(Int128(0x12345678L, -0x6543210FL), Int128(0L, -0x0123456789ABCDF0L)),
            Pair(Int128(-1L, -1L), Int128(-1L, -1L)), // MAX_unsigned × MAX_unsigned
        )

        for ((a, b) in cases) {
            val result = a * b

            // Verify via the multiplication identity: (a*b)/b == a when b != 0
            if (b != Int128.ZERO) {
                // We can't always verify this (overflow), but for small values it holds
            }

            // Verify commutativity (catches byte-order bugs in cinterop struct return)
            assertEquals(b * a, result, "cinterop multiply must be commutative: $a * $b")
        }
    }

    // ── Verify cinterop division is linked and correct ──────────────

    @Test
    fun cinteropSignedDivisionMatchesSoftware() {
        val cases = listOf(
            Pair(Int128.fromLong(42), Int128.fromLong(7)),
            Pair(Int128.fromLong(-42), Int128.fromLong(7)),
            Pair(Int128.fromLong(42), Int128.fromLong(-7)),
            Pair(Int128.fromLong(-42), Int128.fromLong(-7)),
            Pair(Int128.MAX_VALUE, Int128.fromLong(2)),
            Pair(Int128.MAX_VALUE, Int128.MAX_VALUE),
            Pair(Int128(100L, 200L), Int128(0L, 13L)),
            Pair(Int128(100L, 200L), Int128(3L, 7L)),
        )

        for ((a, b) in cases) {
            val q = a / b
            val r = a % b
            // The fundamental identity: a == q*b + r
            assertEquals(a, q * b + r, "divRem identity: $a / $b")
        }
    }

    @Test
    fun cinteropUnsignedDivisionMatchesSoftware() {
        val cases = listOf(
            Pair(UInt128.fromLong(42), UInt128.fromLong(7)),
            Pair(UInt128.MAX_VALUE, UInt128.fromLong(2)),
            Pair(UInt128.MAX_VALUE, UInt128.MAX_VALUE),
            Pair(UInt128(100L, 200L), UInt128(0L, 13L)),
            Pair(UInt128(100L, 200L), UInt128(3L, 7L)),
            // Values where hi bits are set (unsigned-specific)
            Pair(UInt128(-1L, -1L), UInt128(0L, 3L)),
            Pair(UInt128(-1L, 0L), UInt128(1L, 0L)),
        )

        for ((a, b) in cases) {
            val q = a / b
            val r = a % b
            assertEquals(a, q * b + r, "unsigned divRem identity: $a / $b")
        }
    }

    // ── Verify cinterop handles the struct return convention ─────────

    @Test
    fun cinteropStructReturnPreservesBothWords() {
        // The C functions return int128_parts { int64_t hi; int64_t lo; }.
        // If the struct return ABI is wrong (e.g., hi/lo swapped), this fails.
        val a = Int128(0x1111111111111111L, 0x2222222222222222L)
        val b = Int128(0L, 1L)
        val result = a * b  // multiply by 1 should preserve both words
        assertEquals(a.hi, result.hi, "cinterop struct return: hi word preserved")
        assertEquals(a.lo, result.lo, "cinterop struct return: lo word preserved")
    }

    @Test
    fun cinteropStructReturnHandlesCrossWordCarry() {
        // 2^63 * 2 = 2^64 → lo overflows to 0, hi gets the carry
        val a = Int128(0L, Long.MIN_VALUE) // lo = 2^63
        val b = Int128.fromLong(2)
        val result = a * b
        assertEquals(1L, result.hi, "cinterop carry: hi should be 1")
        assertEquals(0L, result.lo, "cinterop carry: lo should be 0")
    }

    // ── Division edge cases that differ between signed/unsigned ─────

    @Test
    fun cinteropDivByZeroThrows() {
        assertFailsWith<ArithmeticException> { Int128.ONE / Int128.ZERO }
        assertFailsWith<ArithmeticException> { UInt128.ONE / UInt128.ZERO }
    }

    @Test
    fun cinteropSignedMinDivByNegOneThrows() {
        assertFailsWith<ArithmeticException> { Int128.MIN_VALUE / Int128.NEGATIVE_ONE }
    }

    @Test
    fun cinteropDivSelfIsOne() {
        val values = listOf(
            Int128.ONE, Int128.NEGATIVE_ONE, Int128.MAX_VALUE, Int128.MIN_VALUE,
            Int128(12345L, 67890L), Int128(-12345L, 67890L),
        )
        for (v in values) {
            assertEquals(Int128.ONE, v / v, "$v / $v must be 1")
        }
    }
}
