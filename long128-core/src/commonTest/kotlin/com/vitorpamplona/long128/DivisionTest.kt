package com.vitorpamplona.long128

import com.ionspin.kotlin.bignum.integer.BigInteger
import kotlin.random.Random
import kotlin.test.*

/**
 * Division against the bignum oracle, aimed at the corner cases of Knuth's algorithm D
 * (JVM path) and `__udivti3` (native path): every operand bit length, divisors on the
 * 32/64-bit boundaries, all-ones and power-of-two patterns, and quotient-digit
 * corrections (dividend words just below the divisor).
 */
class DivisionTest {

    private val TWO_64 = BigInteger.ONE.shl(64)
    private val TWO_128 = BigInteger.ONE.shl(128)

    private fun UInt128.big() = BigInteger.fromULong(hi.toULong()) * TWO_64 + BigInteger.fromULong(lo.toULong())
    private fun Int128.big(): BigInteger {
        val u = BigInteger.fromULong(hi.toULong()) * TWO_64 + BigInteger.fromULong(lo.toULong())
        return if (hi < 0) u - TWO_128 else u
    }
    private fun BigInteger.u128(): UInt128 {
        var m = this % TWO_128
        if (m < BigInteger.ZERO) m += TWO_128
        return UInt128((m shr 64).ulongValue(false).toLong(), (m % TWO_64).ulongValue(false).toLong())
    }

    /** A value with exactly [bits] significant bits (0..128), random below the top bit. */
    private fun withBits(r: Random, bits: Int): UInt128 {
        if (bits == 0) return UInt128.ZERO
        val x = UInt128(r.nextLong(), r.nextLong())
        val top = UInt128.ONE shl (bits - 1)
        return if (bits == 128) x or top else (x and (top - UInt128.ONE)) or top
    }

    private fun edges(): List<UInt128> {
        val out = mutableListOf<UInt128>()
        for (b in listOf(0, 1, 2, 31, 32, 33, 62, 63, 64, 65, 95, 96, 126, 127)) {
            val p = UInt128.ONE shl b
            out += p; out += p - UInt128.ONE; out += p + UInt128.ONE
        }
        out += UInt128.MAX_VALUE
        out += UInt128(-1L, 0L)
        out += UInt128(0L, -1L)
        out += UInt128(Long.MIN_VALUE, 0L)
        out += UInt128(0x7FFFFFFFFFFFFFFFL, -1L)
        out += UInt128(0xFFFFFFFFL, 0xFFFFFFFF00000000uL.toLong())
        out += UInt128(1L, Long.MIN_VALUE)
        return out.filter { it != UInt128.ZERO } + UInt128.ZERO
    }

    private fun checkUnsigned(n: UInt128, d: UInt128) {
        if (d == UInt128.ZERO) {
            assertFailsWith<ArithmeticException> { n / d }
            return
        }
        val nb = n.big(); val db = d.big()
        assertEquals((nb / db).u128(), n / d, "$n / $d")
        assertEquals((nb % db).u128(), n % d, "$n % $d")
    }

    @Test fun unsignedEdgeCross() {
        val e = edges()
        for (n in e) for (d in e) checkUnsigned(n, d)
    }

    @Test fun unsignedAllBitLengths() {
        val r = Random(0xD1D)
        for (nb in 0..128) for (db in 1..128) repeat(3) { checkUnsigned(withBits(r, nb), withBits(r, db)) }
    }

    @Test fun unsignedQuotientDigitCorrections() {
        // Dividend words equal to / just below the divisor's: Knuth D's q-hat overestimates here.
        val r = Random(0xC0FFEE)
        repeat(4000) {
            val dHi = if (r.nextBoolean()) 0L else r.nextLong() or Long.MIN_VALUE ushr r.nextInt(64)
            val dLo = r.nextLong() or 1L
            val d = UInt128(dHi, dLo)
            val k = UInt128(0L, r.nextLong() ushr r.nextInt(64))
            val n = d * k + (d - UInt128.ONE)            // largest dividend with quotient k (if no overflow)
            checkUnsigned(n, d)
            checkUnsigned(n + UInt128.ONE, d)
            checkUnsigned(UInt128(d.hi, -1L), d)
            checkUnsigned(UInt128(dLo, 0L), UInt128(0L, dLo))
            checkUnsigned(UInt128(dLo - 1, -1L), UInt128(0L, dLo))
        }
    }

    @Test fun signedMatchesTruncatedDivision() {
        val r = Random(0x5167)
        val vals = edges().map { it.toInt128() } + List(400) { Int128(r.nextLong(), r.nextLong()) } +
            List(400) { Int128(0L, r.nextLong()) } + List(400) { Int128(-1L, r.nextLong()) }
        for (i in 0 until 3000) {
            val n = vals[r.nextInt(vals.size)]
            val d = vals[r.nextInt(vals.size)]
            if (d == Int128.ZERO) continue
            if (n == Int128.MIN_VALUE && d == Int128.NEGATIVE_ONE) continue
            val nb = n.big(); val db = d.big()
            // Truncated division, like Kotlin's Long: q = sign · (|n| / |d|), r = n - q·d.
            // (Built from magnitudes because bignum's `%` uses a different sign rule.)
            val qMag = nb.abs() / db.abs()
            val q = if ((nb < BigInteger.ZERO) != (db < BigInteger.ZERO)) -qMag else qMag
            assertEquals(q.u128().toInt128(), n / d, "$n / $d")
            assertEquals((nb - q * db).u128().toInt128(), n % d, "$n % $d")
        }
    }

    @Test fun toStringRoundTripsInEveryRadix() {
        val r = Random(0x7057)
        val values = edges() + List(300) { UInt128(r.nextLong(), r.nextLong()) }
        for (v in values) for (radix in 2..36) {
            val s = v.toString(radix)
            assertEquals(v.big().toString(radix), s, "toString($v, $radix)")
            assertEquals(v, UInt128.parseString(s, radix))
        }
    }
}
