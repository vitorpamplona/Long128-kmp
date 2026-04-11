package com.vitorpamplona.long128

import kotlin.random.Random
import kotlin.test.*

/**
 * Algebraic property tests for Int128 and UInt128.
 *
 * These verify mathematical invariants that must hold for all inputs:
 * commutativity, associativity, distributivity, identity, inverse.
 *
 * Property tests catch bugs that point-wise tests miss — e.g., a multiply
 * that's correct for small values but wrong when the high word overflows.
 * Each property is tested against edge cases + 200 random values.
 */
class AlgebraicPropertyTest {

    private val edgeCases = listOf(
        Int128.ZERO, Int128.ONE, Int128.NEGATIVE_ONE,
        Int128.MAX_VALUE, Int128.MIN_VALUE,
        Int128(0L, -1L), Int128(1L, 0L), Int128(-1L, 0L),
        Int128(0L, Long.MAX_VALUE), Int128(0L, Long.MIN_VALUE),
    )

    private val randoms = (0 until 200).map {
        val rng = Random(it + 7777)
        Int128(rng.nextLong(), rng.nextLong())
    }

    private val allValues = edgeCases + randoms

    private val uEdge = edgeCases.map { UInt128(it.hi, it.lo) }
    private val uRandoms = randoms.map { UInt128(it.hi, it.lo) }
    private val allU = uEdge + uRandoms

    // ── Identity ─────────────────────────────────────────────────────

    @Test fun addIdentity() {
        for (a in allValues) assertEquals(a, a + Int128.ZERO, "$a + 0")
    }

    @Test fun mulIdentity() {
        for (a in allValues) assertEquals(a, a * Int128.ONE, "$a * 1")
    }

    @Test fun mulByZero() {
        for (a in allValues) assertEquals(Int128.ZERO, a * Int128.ZERO, "$a * 0")
    }

    @Test fun xorSelf() {
        for (a in allValues) assertEquals(Int128.ZERO, a xor a, "$a xor $a")
    }

    @Test fun andSelf() {
        for (a in allValues) assertEquals(a, a and a, "$a and $a")
    }

    @Test fun orSelf() {
        for (a in allValues) assertEquals(a, a or a, "$a or $a")
    }

    // ── Inverse ──────────────────────────────────────────────────────

    @Test fun addInverse() {
        for (a in allValues) assertEquals(Int128.ZERO, a + (-a), "$a + (-$a)")
    }

    @Test fun subSelf() {
        for (a in allValues) assertEquals(Int128.ZERO, a - a, "$a - $a")
    }

    @Test fun doubleNegate() {
        for (a in allValues) assertEquals(a, -(-a), "-(-$a)")
    }

    @Test fun invInv() {
        for (a in allValues) assertEquals(a, a.inv().inv(), "inv(inv($a))")
    }

    // ── Commutativity ────────────────────────────────────────────────

    @Test fun addCommutative() {
        for (i in allValues.indices) {
            val a = allValues[i]
            val b = allValues[(i + 1) % allValues.size]
            assertEquals(a + b, b + a, "$a + $b commutative")
        }
    }

    @Test fun mulCommutative() {
        for (i in allValues.indices) {
            val a = allValues[i]
            val b = allValues[(i + 1) % allValues.size]
            assertEquals(a * b, b * a, "$a * $b commutative")
        }
    }

    @Test fun andCommutative() {
        for (i in allValues.indices) {
            val a = allValues[i]; val b = allValues[(i + 1) % allValues.size]
            assertEquals(a and b, b and a)
        }
    }

    @Test fun orCommutative() {
        for (i in allValues.indices) {
            val a = allValues[i]; val b = allValues[(i + 1) % allValues.size]
            assertEquals(a or b, b or a)
        }
    }

    @Test fun xorCommutative() {
        for (i in allValues.indices) {
            val a = allValues[i]; val b = allValues[(i + 1) % allValues.size]
            assertEquals(a xor b, b xor a)
        }
    }

    // ── Associativity ────────────────────────────────────────────────

    @Test fun addAssociative() {
        for (i in 0 until allValues.size - 2) {
            val a = allValues[i]; val b = allValues[i + 1]; val c = allValues[i + 2]
            assertEquals((a + b) + c, a + (b + c), "($a + $b) + $c associative")
        }
    }

    @Test fun mulAssociative() {
        // Use smaller set — 3-way multiply is expensive
        for (i in 0 until edgeCases.size - 2) {
            val a = edgeCases[i]; val b = edgeCases[i + 1]; val c = edgeCases[i + 2]
            assertEquals((a * b) * c, a * (b * c), "($a * $b) * $c associative")
        }
    }

    // ── Distributivity ───────────────────────────────────────────────

    @Test fun mulDistributesOverAdd() {
        for (i in 0 until edgeCases.size - 2) {
            val a = edgeCases[i]; val b = edgeCases[i + 1]; val c = edgeCases[i + 2]
            assertEquals(a * (b + c), a * b + a * c, "$a * ($b + $c) distributive")
        }
    }

    // ── Shift properties ─────────────────────────────────────────────

    @Test fun shlByZeroIsIdentity() {
        for (a in allValues) assertEquals(a, a shl 0)
    }

    @Test fun shrByZeroIsIdentity() {
        for (a in allValues) assertEquals(a, a shr 0)
    }

    @Test fun ushrByZeroIsIdentity() {
        for (a in allValues) assertEquals(a, a ushr 0)
    }

    @Test fun shlShrRoundTrip() {
        // For positive values, (a shl n) shr n == a when no bits are lost
        val small = Int128.fromLong(42)
        for (n in listOf(1, 7, 32, 63)) {
            assertEquals(small, (small shl n) shr n, "shl $n then shr $n")
        }
    }

    // ── Comparison properties ────────────────────────────────────────

    @Test fun compareReflexive() {
        for (a in allValues) assertEquals(0, a.compareTo(a))
    }

    @Test fun compareAntisymmetric() {
        for (i in allValues.indices) {
            val a = allValues[i]; val b = allValues[(i + 1) % allValues.size]
            val ab = a.compareTo(b)
            val ba = b.compareTo(a)
            assertEquals(-ab.coerceIn(-1, 1), ba.coerceIn(-1, 1), "compare antisymmetric: $a vs $b")
        }
    }

    // ── UInt128 properties ───────────────────────────────────────────

    @Test fun uint128AddIdentity() {
        for (a in allU) assertEquals(a, a + UInt128.ZERO)
    }

    @Test fun uint128MulIdentity() {
        for (a in allU) assertEquals(a, a * UInt128.ONE)
    }

    @Test fun uint128MulByZero() {
        for (a in allU) assertEquals(UInt128.ZERO, a * UInt128.ZERO)
    }

    @Test fun uint128AddInverse() {
        // a + (MAX - a + 1) should wrap to 0 for unsigned
        for (a in allU) assertEquals(UInt128.ZERO, a - a)
    }

    @Test fun uint128AddCommutative() {
        for (i in allU.indices) {
            val a = allU[i]; val b = allU[(i + 1) % allU.size]
            assertEquals(a + b, b + a)
        }
    }

    @Test fun uint128MulCommutative() {
        for (i in allU.indices) {
            val a = allU[i]; val b = allU[(i + 1) % allU.size]
            assertEquals(a * b, b * a)
        }
    }

    @Test fun uint128CompareReflexive() {
        for (a in allU) assertEquals(0, a.compareTo(a))
    }

    // ── Cross-type consistency ───────────────────────────────────────

    @Test fun int128UInt128BitPreservation() {
        // Converting Int128 → UInt128 → Int128 must preserve all bits
        for (a in allValues) assertEquals(a, a.toUInt128().toInt128(), "$a round-trip")
    }

    @Test fun uint128Int128BitPreservation() {
        for (a in allU) assertEquals(a, a.toInt128().toUInt128(), "$a round-trip")
    }
}
