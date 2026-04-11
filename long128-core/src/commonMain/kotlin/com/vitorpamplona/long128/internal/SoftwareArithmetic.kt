package com.vitorpamplona.long128.internal

/**
 * Pure-Kotlin 128-bit arithmetic routines.
 *
 * These implementations work on all platforms without any native interop.
 * They serve as:
 * 1. The actual implementation on JVM (where `__int128` is unavailable)
 * 2. A reference implementation for testing platform-specific code
 * 3. A fallback if cinterop is unavailable on a native target
 *
 * All multi-word values use the convention: the "high" word holds the
 * most-significant 64 bits and the "low" word holds the least-significant
 * 64 bits, treated as unsigned (even though Kotlin's `Long` is signed).
 */

// ─── Comparison ──────────────────────────────────────────────────────

/**
 * Unsigned 128-bit comparison.
 *
 * Compares two 128-bit values where both halves are treated as unsigned.
 * Returns negative, zero, or positive following [Comparable] conventions.
 *
 * Used by [com.vitorpamplona.long128.UInt128.compareTo].
 */
internal fun compareUnsigned128(aHi: Long, aLo: Long, bHi: Long, bLo: Long): Int {
    val hiCmp = aHi.toULong().compareTo(bHi.toULong())
    return if (hiCmp != 0) hiCmp else aLo.toULong().compareTo(bLo.toULong())
}

// ─── Bit counting ────────────────────────────────────────────────────

/** Count leading zero bits in a 128-bit value. Returns 0..128. */
internal fun countLeadingZeros128(hi: Long, lo: Long): Int =
    if (hi != 0L) hi.countLeadingZeroBits() else 64 + lo.countLeadingZeroBits()

// ─── Division ────────────────────────────────────────────────────────

/**
 * Unsigned 128-bit divided by 64-bit.
 *
 * This is the fast path used by [toString] (dividing by a radix power)
 * and by the general 128÷128 division when the divisor fits in 64 bits.
 *
 * Returns `[quotientHi, quotientLo, 0, remainder]`.
 *
 * Algorithm: standard schoolbook long division, processing the low
 * 64-bit word one bit at a time via shift-and-subtract. The high word
 * is divided first to produce the high quotient word, then the remainder
 * chains into the low word division.
 */
internal fun unsignedDivRemBy64(dividendHi: Long, dividendLo: Long, divisor: Long): LongArray {
    val dU = divisor.toULong()
    val nHiU = dividendHi.toULong()
    val nLoU = dividendLo.toULong()

    if (nHiU == 0.toULong()) {
        return longArrayOf(0L, (nLoU / dU).toLong(), 0L, (nLoU % dU).toLong())
    }

    val qHiU = nHiU / dU
    var remainder = nHiU % dU
    var qLoU: ULong = 0u

    // Process each bit of the low word, carrying the remainder from the high word.
    // On each iteration: shift remainder left by 1, bring in the next dividend bit,
    // subtract divisor if remainder >= divisor. This compiles to branchless code
    // on most architectures.
    for (i in 63 downTo 0) {
        val bit = (nLoU shr i) and 1u
        val overflow = remainder shr 63 // will be lost in the left shift
        remainder = (remainder shl 1) or bit
        if (overflow != 0.toULong() || remainder >= dU) {
            remainder -= dU
            qLoU = qLoU or (1.toULong() shl i)
        }
    }

    return longArrayOf(qHiU.toLong(), qLoU.toLong(), 0L, remainder.toLong())
}

/**
 * Unsigned 128-bit divided by 128-bit, with remainder.
 *
 * Returns `[quotientHi, quotientLo, remainderHi, remainderLo]`.
 *
 * Algorithm:
 * 1. Fast exit if dividend < divisor (quotient=0, remainder=dividend)
 * 2. Fast path if divisor fits in 64 bits → delegates to [unsignedDivRemBy64]
 * 3. General case: binary long-division (shift-and-subtract), processing one
 *    bit per iteration from the MSB of the dividend downward. Iteration count
 *    is `128 - countLeadingZeros(dividend)`, so small dividends are fast.
 *
 * @throws ArithmeticException if divisor is zero.
 */
internal fun unsignedDivRem(
    dividendHi: Long, dividendLo: Long,
    divisorHi: Long, divisorLo: Long,
): LongArray {
    if (divisorHi == 0L && divisorLo == 0L) throw ArithmeticException("Division by zero")

    val cmp = compareUnsigned128(dividendHi, dividendLo, divisorHi, divisorLo)
    if (cmp < 0) return longArrayOf(0L, 0L, dividendHi, dividendLo)
    if (cmp == 0) return longArrayOf(0L, 1L, 0L, 0L)

    // When the divisor fits in a single 64-bit word, use the faster O(64) path.
    if (divisorHi == 0L) return unsignedDivRemBy64(dividendHi, dividendLo, divisorLo)

    // General binary long-division.
    // We only iterate from the MSB of the dividend, not from bit 127.
    var qHi = 0L; var qLo = 0L
    var rHi = 0L; var rLo = 0L

    val msbPosition = 127 - countLeadingZeros128(dividendHi, dividendLo)

    for (i in msbPosition downTo 0) {
        // Shift remainder left by 1
        rHi = (rHi shl 1) or (rLo ushr 63)
        rLo = rLo shl 1

        // Bring down bit i of the dividend
        val bit = if (i >= 64) (dividendHi ushr (i - 64)) and 1L else (dividendLo ushr i) and 1L
        rLo = rLo or bit

        // If remainder >= divisor, subtract divisor and set quotient bit
        if (compareUnsigned128(rHi, rLo, divisorHi, divisorLo) >= 0) {
            val newLo = rLo - divisorLo
            val borrow = if (rLo.toULong() < divisorLo.toULong()) 1L else 0L
            rHi = rHi - divisorHi - borrow
            rLo = newLo
            if (i >= 64) qHi = qHi or (1L shl (i - 64)) else qLo = qLo or (1L shl i)
        }
    }
    return longArrayOf(qHi, qLo, rHi, rLo)
}

// ─── String conversion ───────────────────────────────────────────────

private val RADIX_DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray()

/**
 * Converts an unsigned 128-bit value to its string representation in the given radix.
 *
 * Algorithm: repeatedly divides by [radix] using [unsignedDivRemBy64], collecting
 * the remainder as the next least-significant digit. This is O(n * 64) where n
 * is the number of digits (up to 39 for decimal, 128 for binary).
 *
 * For values that fit in a single [ULong], delegates to stdlib's [ULong.toString]
 * to avoid the division loop entirely.
 */
internal fun unsignedToString(hi: Long, lo: Long, radix: Int): String {
    require(radix in 2..36) { "Radix must be in 2..36" }
    if (hi == 0L && lo == 0L) return "0"
    if (hi == 0L) return lo.toULong().toString(radix)

    val buf = StringBuilder()
    var curHi = hi; var curLo = lo

    while (curHi != 0L || curLo != 0L) {
        val result = unsignedDivRemBy64(curHi, curLo, radix.toLong())
        curHi = result[0]; curLo = result[1]
        buf.append(RADIX_DIGITS[result[3].toInt()])
    }

    return buf.reverse().toString()
}

// ─── String parsing ──────────────────────────────────────────────────

/**
 * Parses an unsigned 128-bit integer from a string in the given radix.
 *
 * Returns `[hi, lo]`. Does not handle sign characters — the caller must
 * strip any leading `+` or `-`.
 *
 * Algorithm: for each digit, multiplies the accumulator by [radix] and adds
 * the digit value. Uses [unsignedMultiplyHigh] for the 64×64→128 cross-word
 * multiplication to avoid overflow.
 *
 * @throws NumberFormatException if the string contains invalid characters.
 */
internal fun parseUnsigned128(text: String, radix: Int): LongArray {
    require(text.isNotEmpty()) { "Empty string" }
    require(radix in 2..36) { "Radix must be in 2..36" }

    var hi = 0L; var lo = 0L
    val radixL = radix.toLong()

    for (ch in text) {
        val digit = ch.digitToIntOrNull(radix)
            ?: throw NumberFormatException("Invalid character '$ch' for radix $radix")

        // accumulator = accumulator * radix + digit
        val crossHi = unsignedMultiplyHigh(lo, radixL)
        val crossLo = lo * radixL
        hi = crossHi + hi * radixL

        val newLo = crossLo + digit.toLong()
        val carry = if (newLo.toULong() < crossLo.toULong()) 1L else 0L
        lo = newLo
        hi += carry
    }

    return longArrayOf(hi, lo)
}
