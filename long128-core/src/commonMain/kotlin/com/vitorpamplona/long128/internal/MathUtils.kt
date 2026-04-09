package com.vitorpamplona.long128.internal

// ---------------------------------------------------------------------------
// Unsigned 128-bit comparison
// ---------------------------------------------------------------------------

internal fun ucmp128(aHi: Long, aLo: Long, bHi: Long, bLo: Long): Int {
    val hiCmp = aHi.toULong().compareTo(bHi.toULong())
    return if (hiCmp != 0) hiCmp else aLo.toULong().compareTo(bLo.toULong())
}

// ---------------------------------------------------------------------------
// Count leading zeros
// ---------------------------------------------------------------------------

internal fun clz128(hi: Long, lo: Long): Int =
    if (hi != 0L) hi.countLeadingZeroBits() else 64 + lo.countLeadingZeroBits()

// ---------------------------------------------------------------------------
// Unsigned 128 / 64 division
// Returns LongArray [quotientHi, quotientLo, 0, remainder]
// ---------------------------------------------------------------------------

internal fun udivrem128by64(nHi: Long, nLo: Long, d: Long): LongArray {
    val dU = d.toULong()
    val nHiU = nHi.toULong()
    val nLoU = nLo.toULong()

    if (nHiU == 0.toULong()) {
        return longArrayOf(0L, (nLoU / dU).toLong(), 0L, (nLoU % dU).toLong())
    }

    val qHiU = nHiU / dU
    var rem = nHiU % dU
    var qLoU: ULong = 0u

    for (i in 63 downTo 0) {
        val bit = (nLoU shr i) and 1u
        val carry = rem shr 63
        rem = (rem shl 1) or bit
        if (carry != 0.toULong() || rem >= dU) {
            rem -= dU
            qLoU = qLoU or (1.toULong() shl i)
        }
    }

    return longArrayOf(qHiU.toLong(), qLoU.toLong(), 0L, rem.toLong())
}

// ---------------------------------------------------------------------------
// Unsigned 128 / 128 division  (binary long-division)
// Returns LongArray [qHi, qLo, rHi, rLo]
// ---------------------------------------------------------------------------

internal fun udivrem128(nHi: Long, nLo: Long, dHi: Long, dLo: Long): LongArray {
    if (dHi == 0L && dLo == 0L) throw ArithmeticException("Division by zero")

    val cmp = ucmp128(nHi, nLo, dHi, dLo)
    if (cmp < 0) return longArrayOf(0L, 0L, nHi, nLo)
    if (cmp == 0) return longArrayOf(0L, 1L, 0L, 0L)

    // Fast path: divisor fits in 64 bits
    if (dHi == 0L) return udivrem128by64(nHi, nLo, dLo)

    // General binary long-division
    var qHi = 0L; var qLo = 0L
    var rHi = 0L; var rLo = 0L

    val startBit = 127 - clz128(nHi, nLo)

    for (i in startBit downTo 0) {
        // Shift remainder left by 1
        rHi = (rHi shl 1) or (rLo ushr 63)
        rLo = rLo shl 1

        // Bring down bit i of the dividend
        val bit = if (i >= 64) (nHi ushr (i - 64)) and 1L else (nLo ushr i) and 1L
        rLo = rLo or bit

        // If remainder >= divisor -> subtract and set quotient bit
        if (ucmp128(rHi, rLo, dHi, dLo) >= 0) {
            val newLo = rLo - dLo
            val borrow = if (rLo.toULong() < dLo.toULong()) 1L else 0L
            rHi = rHi - dHi - borrow
            rLo = newLo
            if (i >= 64) qHi = qHi or (1L shl (i - 64)) else qLo = qLo or (1L shl i)
        }
    }
    return longArrayOf(qHi, qLo, rHi, rLo)
}

// ---------------------------------------------------------------------------
// Unsigned 128-bit -> String
// ---------------------------------------------------------------------------

private val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz".toCharArray()

internal fun uToString128(hi: Long, lo: Long, radix: Int): String {
    require(radix in 2..36) { "Radix must be in 2..36" }
    if (hi == 0L && lo == 0L) return "0"
    if (hi == 0L) return lo.toULong().toString(radix)

    val buf = StringBuilder()
    var curHi = hi; var curLo = lo

    while (curHi != 0L || curLo != 0L) {
        val r = udivrem128by64(curHi, curLo, radix.toLong())
        curHi = r[0]; curLo = r[1]
        buf.append(DIGITS[r[3].toInt()])
    }

    return buf.reverse().toString()
}

// ---------------------------------------------------------------------------
// String -> Unsigned 128-bit
// ---------------------------------------------------------------------------

internal fun parseUInt128(s: String, radix: Int): LongArray {
    require(s.isNotEmpty()) { "Empty string" }
    require(radix in 2..36) { "Radix must be in 2..36" }

    var hi = 0L; var lo = 0L
    val radixL = radix.toLong()

    for (ch in s) {
        val digit = ch.digitToIntOrNull(radix)
            ?: throw NumberFormatException("Invalid character '$ch' for radix $radix")

        // (hi, lo) = (hi, lo) * radix + digit
        val mulHi = platformUnsignedMultiplyHigh(lo, radixL)
        val mulLo = lo * radixL
        hi = mulHi + hi * radixL

        val newLo = mulLo + digit.toLong()
        val carry = if (newLo.toULong() < mulLo.toULong()) 1L else 0L
        lo = newLo
        hi += carry
    }

    return longArrayOf(hi, lo)
}
