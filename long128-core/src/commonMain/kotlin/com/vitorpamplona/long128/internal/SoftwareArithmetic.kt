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
//
// Knuth's algorithm D specialised to 128-bit operands, built on the hardware's
// 64-bit unsigned division ([divideUnsigned64]: `Long.divideUnsigned` on the JVM,
// an intrinsic `div` from JDK 19). It is the structure of libgcc's `__udivti3`,
// except that x86 can divide 128 by 64 bits in one instruction and portable code
// cannot, so the 128/64 step ([divide128By64]) is two 64/32-digit divisions with
// Knuth's quotient-digit correction (Hacker's Delight §9.2 `divlu`; Go's
// `math/bits.Div64` is the same routine).
//
// Native targets do not use this for `Int128`/`UInt128` division (they call
// `__udivti3` directly); it backs the JVM and `toString` everywhere.

private const val MASK32 = 0xFFFFFFFFL
private const val TWO32 = 1L shl 32

private fun ult(a: Long, b: Long): Boolean = (a xor Long.MIN_VALUE) < (b xor Long.MIN_VALUE)
private fun ugt(a: Long, b: Long): Boolean = (a xor Long.MIN_VALUE) > (b xor Long.MIN_VALUE)

/**
 * `floor((hi·2^64 + lo) / d)`, all unsigned, for `hi < d` (so the quotient fits in
 * 64 bits). The caller derives the remainder as `lo - q·d`.
 */
internal fun divide128By64(hi: Long, lo: Long, d: Long): Long {
    if (hi == 0L) return divideUnsigned64(lo, d)

    // Normalize: shift the divisor until its top bit is set, so each estimated
    // quotient digit is off by at most 2.
    val s = d.countLeadingZeroBits()
    val v = d shl s
    val vn1 = v ushr 32
    val vn0 = v and MASK32
    val un32 = if (s == 0) hi else (hi shl s) or (lo ushr (64 - s))
    val un10 = lo shl s
    val un1 = un10 ushr 32
    val un0 = un10 and MASK32

    // High 32-bit digit of the quotient.
    var q1 = divideUnsigned64(un32, vn1)
    var rhat = un32 - q1 * vn1
    while (q1 >= TWO32 || ugt(q1 * vn0, (rhat shl 32) + un1)) {
        q1--
        rhat += vn1
        if (rhat >= TWO32) break
    }

    // Low 32-bit digit.
    val un21 = (un32 shl 32) + un1 - q1 * v
    var q0 = divideUnsigned64(un21, vn1)
    rhat = un21 - q0 * vn1
    while (q0 >= TWO32 || ugt(q0 * vn0, (rhat shl 32) + un0)) {
        q0--
        rhat += vn1
        if (rhat >= TWO32) break
    }
    return (q1 shl 32) + q0
}

private fun lessThan128(aHi: Long, aLo: Long, bHi: Long, bLo: Long): Boolean =
    ult(aHi, bHi) || (aHi == bHi && ult(aLo, bLo))

/**
 * The exact quotient of `n / d` for `dHi != 0` and `n >= d` (it fits in 64 bits).
 *
 * Estimates from the top 64 bits of the normalized divisor, dividing the halved
 * dividend so the 128/64 step cannot overflow; after undoing both the estimate is
 * exact or one too large (Hacker's Delight §9.5, `divdu`), fixed with one compare.
 */
private fun quotientWide(nHi: Long, nLo: Long, dHi: Long, dLo: Long): Long {
    val s = dHi.countLeadingZeroBits()
    val v1 = if (s == 0) dHi else (dHi shl s) or (dLo ushr (64 - s))
    var q = divide128By64(nHi ushr 1, (nLo ushr 1) or (nHi shl 63), v1) ushr (63 - s)
    if (q != 0L) q--
    // r = n - q·d; if r >= d the estimate was one short.
    val pLo = q * dLo
    val pHi = unsignedMultiplyHigh(q, dLo) + q * dHi
    val rLo = nLo - pLo
    val rHi = nHi - pHi - (if (ult(nLo, pLo)) 1L else 0L)
    return if (lessThan128(rHi, rLo, dHi, dLo)) q else q + 1
}

// The quotient and remainder functions compute both result words through the
// branches and construct the result once, at the end. With one allocation site,
// HotSpot's escape analysis can scalar-replace the result when the call inlines;
// a result merged from several sites (or from a shared constant like ZERO)
// stays a heap object.

/**
 * Unsigned 128-bit quotient. Separate from [unsignedRemainder] (rather than one
 * function returning both) so neither allocates nor boxes, and each does only the
 * work its result needs.
 *
 * @throws ArithmeticException if the divisor is zero.
 */
internal fun unsignedQuotient(nHi: Long, nLo: Long, dHi: Long, dLo: Long): com.vitorpamplona.long128.UInt128 {
    var qHi = 0L
    val qLo: Long
    if (dHi == 0L) {
        if (dLo == 0L) throw ArithmeticException("Division by zero")
        qLo = if (nHi == 0L) {
            divideUnsigned64(nLo, dLo)
        } else if (ult(nHi, dLo)) {
            divide128By64(nHi, nLo, dLo)
        } else {
            qHi = divideUnsigned64(nHi, dLo)
            divide128By64(nHi - qHi * dLo, nLo, dLo)
        }
    } else {
        qLo = if (lessThan128(nHi, nLo, dHi, dLo)) 0L else quotientWide(nHi, nLo, dHi, dLo)
    }
    return com.vitorpamplona.long128.UInt128(qHi, qLo)
}

/**
 * Unsigned 128-bit remainder.
 *
 * @throws ArithmeticException if the divisor is zero.
 */
internal fun unsignedRemainder(nHi: Long, nLo: Long, dHi: Long, dLo: Long): com.vitorpamplona.long128.UInt128 {
    val rHi: Long
    val rLo: Long
    if (dHi == 0L) {
        if (dLo == 0L) throw ArithmeticException("Division by zero")
        rHi = 0L
        rLo = if (nHi == 0L) {
            nLo - divideUnsigned64(nLo, dLo) * dLo
        } else {
            val r1 = if (ult(nHi, dLo)) nHi else nHi - divideUnsigned64(nHi, dLo) * dLo   // nHi mod d
            nLo - divide128By64(r1, nLo, dLo) * dLo
        }
    } else if (lessThan128(nHi, nLo, dHi, dLo)) {
        rHi = nHi; rLo = nLo
    } else {
        val q = quotientWide(nHi, nLo, dHi, dLo)
        val pLo = q * dLo
        val pHi = unsignedMultiplyHigh(q, dLo) + q * dHi
        rHi = nHi - pHi - (if (ult(nLo, pLo)) 1L else 0L)
        rLo = nLo - pLo
    }
    return com.vitorpamplona.long128.UInt128(rHi, rLo)
}

// ─── String conversion ───────────────────────────────────────────────

/**
 * Converts an unsigned 128-bit value to its string representation in the given radix.
 *
 * Divides by the largest power of [radix] that fits in a signed `Long` (10^18 for
 * decimal), so a 39-digit value takes at most three 128-bit divisions; each chunk
 * is printed by the stdlib's `Long.toString` and zero-padded.
 */
internal fun unsignedToString(hi: Long, lo: Long, radix: Int): String {
    require(radix in 2..36) { "Radix must be in 2..36" }
    if (hi == 0L) return lo.toULong().toString(radix)

    var chunk = radix.toLong()
    var digits = 1
    while (chunk <= Long.MAX_VALUE / radix) { chunk *= radix; digits++ }

    val parts = ArrayList<String>(4)
    var curHi = hi; var curLo = lo
    while (curHi != 0L) {
        // (curHi, curLo) / chunk: high word first, then 128/64 on (remainder, curLo)
        val qHi = divideUnsigned64(curHi, chunk)
        val qLo = divide128By64(curHi - qHi * chunk, curLo, chunk)
        parts.add((curLo - qLo * chunk).toString(radix).padStart(digits, '0'))
        curHi = qHi; curLo = qLo
    }
    val sb = StringBuilder(curLo.toULong().toString(radix))
    for (i in parts.indices.reversed()) sb.append(parts[i])
    return sb.toString()
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
