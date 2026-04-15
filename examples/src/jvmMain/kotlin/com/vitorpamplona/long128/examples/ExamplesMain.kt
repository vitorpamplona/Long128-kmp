package com.vitorpamplona.long128.examples

import com.vitorpamplona.long128.Int128
import com.vitorpamplona.long128.Int128Array
import com.vitorpamplona.long128.UInt128
import com.vitorpamplona.long128.UInt128Array
import com.vitorpamplona.long128.abs
import com.vitorpamplona.long128.addExact
import com.vitorpamplona.long128.int128ArrayOf
import com.vitorpamplona.long128.maxOf
import com.vitorpamplona.long128.minOf
import com.vitorpamplona.long128.negateExact
import com.vitorpamplona.long128.sign
import com.vitorpamplona.long128.subtractExact
import com.vitorpamplona.long128.toInt128
import com.vitorpamplona.long128.toInt128OrNull
import com.vitorpamplona.long128.toUInt128
import com.vitorpamplona.long128.toUInt128OrNull
import com.vitorpamplona.long128.uint128ArrayOf

/**
 * Runnable showcase for every public API in `long128-core`.
 *
 * Run with:
 *     ./gradlew -q :examples:jvmRun
 *
 * The file is organized into sections that each focus on one capability of the
 * library. Every example prints its input(s) and the result so you can read
 * the output top-to-bottom and see exactly how the types behave. Nothing here
 * is platform-specific — the same code runs on every Kotlin target supported
 * by the library (JVM, Android, iOS, macOS, Linux, Windows).
 */
fun main() {
    section("1. Int128: construction")         { int128Construction() }
    section("2. Int128: wrapping arithmetic")  { int128Arithmetic() }
    section("3. Int128: bitwise operations")   { int128Bitwise() }
    section("4. Int128: shifts")               { int128Shifts() }
    section("5. Int128: comparison & order")   { int128Comparison() }
    section("6. Int128: narrowing conversions"){ int128Conversions() }
    section("7. Int128: parsing & toString")   { int128Strings() }
    section("8. Int128: bit utilities")        { int128BitUtilities() }
    section("9. Int128: checked arithmetic")   { int128CheckedArithmetic() }
    section("10. UInt128: construction")       { uint128Construction() }
    section("11. UInt128: arithmetic")         { uint128Arithmetic() }
    section("12. UInt128: comparison & shr")   { uint128ComparisonAndShr() }
    section("13. UInt128: parsing & toString") { uint128Strings() }
    section("14. UInt128: checked arithmetic") { uint128CheckedArithmetic() }
    section("15. Signed ↔ unsigned interop")   { signedUnsignedInterop() }
    section("16. Int128Array: flat storage")   { int128ArrayExamples() }
    section("17. UInt128Array: flat storage")  { uint128ArrayExamples() }
    section("18. Case study: UUID as UInt128") { uuidCaseStudy() }
    section("19. Case study: factorials")      { factorialCaseStudy() }
    section("20. Case study: running sum")     { runningSumCaseStudy() }
}

// ─── Section 1 ────────────────────────────────────────────────────────────────

private fun int128Construction() {
    // Constants exposed on the companion object.
    show("Int128.ZERO",         Int128.ZERO)
    show("Int128.ONE",          Int128.ONE)
    show("Int128.NEGATIVE_ONE", Int128.NEGATIVE_ONE)
    show("Int128.MAX_VALUE",    Int128.MAX_VALUE)      //  2^127 - 1
    show("Int128.MIN_VALUE",    Int128.MIN_VALUE)      // -2^127
    show("Int128.SIZE_BITS",    Int128.SIZE_BITS)
    show("Int128.SIZE_BYTES",   Int128.SIZE_BYTES)

    // From primitive types. `fromLong` sign-extends into the high word, so a
    // negative Long becomes a negative Int128.
    show("Int128.fromLong( 42)",           Int128.fromLong(42L))
    show("Int128.fromLong(-42)",           Int128.fromLong(-42L))
    show("Int128.fromInt(Int.MIN_VALUE)",  Int128.fromInt(Int.MIN_VALUE))
    show("Long.MAX_VALUE.toInt128()",      Long.MAX_VALUE.toInt128())
    show("(-7).toInt128()",                (-7).toInt128())

    // Raw two-Long constructor — use this when you already have the halves in
    // hand (for instance after reading from a binary format).
    show("Int128(hi=1, lo=0)", Int128(hi = 1L, lo = 0L)) // == 2^64
}

// ─── Section 2 ────────────────────────────────────────────────────────────────

private fun int128Arithmetic() {
    val a = Int128.fromLong(Long.MAX_VALUE)
    val b = Int128.fromLong(2L)
    show("a = Long.MAX_VALUE",     a)
    show("b = 2",                  b)
    show("a + b",                  a + b)   // no overflow at 128 bits
    show("a - b",                  a - b)
    show("a * b",                  a * b)   // full 128-bit multiply
    show("a / b",                  a / b)
    show("a % b",                  a % b)
    show("-a",                     -a)
    show("+a",                     +a)

    // `inc` / `dec` operators support `++` / `--`.
    var counter = Int128.fromLong(10L)
    counter++
    counter++
    counter--
    show("after ++, ++, --",       counter)

    // Wrapping is deliberate — it matches Kotlin's `Int` / `Long` semantics.
    show("MAX_VALUE + ONE (wraps)", Int128.MAX_VALUE + Int128.ONE) // == MIN_VALUE
    show("MIN_VALUE - ONE (wraps)", Int128.MIN_VALUE - Int128.ONE) // == MAX_VALUE
}

// ─── Section 3 ────────────────────────────────────────────────────────────────

private fun int128Bitwise() {
    // Parser accepts plain digits for the given radix — no "0x" prefix.
    val mask  = "ffffffffffffffff".toInt128(16)  // low 64 bits: 2^64 - 1
    val all   = Int128.NEGATIVE_ONE              // every bit set
    val upper = all xor mask                     // only hi bits

    show("low-64 mask",     mask)
    show("all-ones",        all)
    show("upper = all xor mask", upper)
    show("mask and upper",  mask and upper)  // 0
    show("mask or  upper",  mask or upper)   // -1
    show("mask xor upper",  mask xor upper)  // -1
    show("mask.inv()",      mask.inv())      // equals `upper`
}

// ─── Section 4 ────────────────────────────────────────────────────────────────

private fun int128Shifts() {
    val one = Int128.ONE
    show("ONE shl   0", one shl 0)
    show("ONE shl  63", one shl 63)
    show("ONE shl  64", one shl 64)
    show("ONE shl 127", one shl 127) // == Int128.MIN_VALUE

    val negative = Int128.fromLong(-1L) shl 100
    show("-1 shl 100",       negative)
    show("... shr   1",      negative shr 1)   // arithmetic: fills with sign bit
    show("... ushr  1",      negative ushr 1)  // logical:    fills with zero

    // Shift amounts are masked to 0..127, so `shl 128` is a no-op.
    show("ONE shl 128 (==0)", one shl 128)
}

// ─── Section 5 ────────────────────────────────────────────────────────────────

private fun int128Comparison() {
    val a = Int128.fromLong(-5L)
    val b = Int128.fromLong(5L)
    show("a", a); show("b", b)
    show("a < b",  a < b)
    show("a == b", a == b)
    show("a.compareTo(b)", a.compareTo(b))
    show("maxOf(a, b)",    maxOf(a, b))
    show("minOf(a, b)",    minOf(a, b))
    show("a.isNegative()", a.isNegative())
    show("a.sign",         a.sign) // -1, 0, or 1
}

// ─── Section 6 ────────────────────────────────────────────────────────────────

private fun int128Conversions() {
    val big = "123456789012345678901234567890".toInt128()
    show("big (decimal)", big)

    // Narrowing conversions truncate — the low bits survive, the rest are
    // dropped. Same behaviour as Kotlin's `Long.toInt()`.
    show("big.toByte()",   big.toByte())
    show("big.toShort()",  big.toShort())
    show("big.toInt()",    big.toInt())
    show("big.toLong()",   big.toLong())

    // Floating-point conversions preserve magnitude but lose precision beyond
    // 53 bits of mantissa.
    show("big.toFloat()",  big.toFloat())
    show("big.toDouble()", big.toDouble())

    // Reinterpret bits as unsigned (no change, just a different view).
    show("big.toUInt128()", big.toUInt128())
}

// ─── Section 7 ────────────────────────────────────────────────────────────────

private fun int128Strings() {
    val value = "-170141183460469231731687303715884105727".toInt128()
    show("parse decimal",    value)
    show("toString()",       value.toString())
    show("toString(16)",     value.toString(16))
    show("toString(2)",      value.toString(2))

    // Radix-16 parsing, with optional sign.
    val hex = "deadbeefcafebabe1234567890abcdef".toInt128(16)
    show("parse hex",        hex)
    show("hex.toString(16)", hex.toString(16))

    // `OrNull` variants never throw — handy for untrusted input.
    show("\"oops\".toInt128OrNull()", "oops".toInt128OrNull())
    show("Int128.parseStringOrNull(\"99\")", Int128.parseStringOrNull("99"))
}

// ─── Section 8 ────────────────────────────────────────────────────────────────

private fun int128BitUtilities() {
    val v = Int128.ONE shl 100 // one bit set at position 100
    show("v",                       v)
    show("v.countLeadingZeroBits",  v.countLeadingZeroBits())  // 27
    show("v.countTrailingZeroBits", v.countTrailingZeroBits()) // 100
    show("v.countOneBits",          v.countOneBits())          // 1
    show("(-1).countOneBits",       Int128.NEGATIVE_ONE.countOneBits()) // 128
}

// ─── Section 9 ────────────────────────────────────────────────────────────────

private fun int128CheckedArithmetic() {
    // `addExact` / `subtractExact` / `negateExact` throw on overflow instead
    // of wrapping — mirroring `Math.addExact` in the JDK.
    show("MAX - 1 addExact 1", (Int128.MAX_VALUE - Int128.ONE).addExact(Int128.ONE))
    try {
        Int128.MAX_VALUE.addExact(Int128.ONE)
    } catch (e: ArithmeticException) {
        show("MAX addExact 1 → throws", e.message)
    }
    try {
        Int128.MIN_VALUE.negateExact()
    } catch (e: ArithmeticException) {
        show("MIN.negateExact() → throws", e.message)
    }
    try {
        Int128.MIN_VALUE.abs()
    } catch (e: ArithmeticException) {
        show("MIN.abs() → throws", e.message)
    }

    show("(-7).toInt128().abs()", (-7).toInt128().abs())
    // subtractExact: fine here.
    show("0 subtractExact 1", Int128.ZERO.subtractExact(Int128.ONE))
}

// ─── Section 10 ───────────────────────────────────────────────────────────────

private fun uint128Construction() {
    show("UInt128.ZERO",      UInt128.ZERO)
    show("UInt128.ONE",       UInt128.ONE)
    show("UInt128.MIN_VALUE", UInt128.MIN_VALUE) // == ZERO
    show("UInt128.MAX_VALUE", UInt128.MAX_VALUE) // 2^128 - 1
    show("UInt128.SIZE_BITS", UInt128.SIZE_BITS)

    // `fromLong` does NOT sign-extend: it's taken as an unsigned 64-bit value.
    show("UInt128.fromLong(-1)", UInt128.fromLong(-1L))   // == 2^64 - 1
    show("UInt128.fromULong(ULong.MAX)", UInt128.fromULong(ULong.MAX_VALUE))
    show("1u.toUInt128()",       1u.toUInt128())
    show("2uL.toUInt128()",      2uL.toUInt128())
}

// ─── Section 11 ───────────────────────────────────────────────────────────────

private fun uint128Arithmetic() {
    val a = UInt128.fromULong(ULong.MAX_VALUE) // 2^64 - 1
    val b = UInt128.fromLong(3L)
    show("a = 2^64 - 1", a)
    show("b = 3",        b)
    show("a + b", a + b)
    show("a * b", a * b)
    show("a / b", a / b)
    show("a % b", a % b)

    // Subtraction wraps modulo 2^128, just like `UInt`.
    show("ZERO - ONE (wraps)", UInt128.ZERO - UInt128.ONE) // == MAX_VALUE

    var ticker = UInt128.fromLong(100L)
    ticker--
    ticker++
    show("ticker after -- then ++", ticker)
}

// ─── Section 12 ───────────────────────────────────────────────────────────────

private fun uint128ComparisonAndShr() {
    // Under unsigned ordering, all-ones is the largest value — for Int128 it
    // is -1, but for UInt128 it is 2^128 - 1.
    val allOnes  = UInt128(-1L, -1L)
    val halfway  = UInt128(Long.MAX_VALUE, -1L)
    show("allOnes",  allOnes)
    show("halfway",  halfway)
    show("allOnes > halfway", allOnes > halfway) // true (unsigned)
    show("maxOf(allOnes, halfway)", maxOf(allOnes, halfway))
    show("minOf(allOnes, halfway)", minOf(allOnes, halfway))

    // UInt128.shr is ALWAYS logical (zero-fill). There is no arithmetic right
    // shift because unsigned values have no sign bit.
    show("MAX shr 1",  UInt128.MAX_VALUE shr 1)
    show("ONE shl 127", UInt128.ONE shl 127)

    // Bitwise operators, same shape as Int128.
    show("MAX and ONE", UInt128.MAX_VALUE and UInt128.ONE)
    show("ZERO.inv()",  UInt128.ZERO.inv()) // == MAX_VALUE

    show("MAX.countLeadingZeroBits",  UInt128.MAX_VALUE.countLeadingZeroBits())
    show("ONE.countTrailingZeroBits", UInt128.ONE.countTrailingZeroBits())
    show("MAX.countOneBits",          UInt128.MAX_VALUE.countOneBits())
}

// ─── Section 13 ───────────────────────────────────────────────────────────────

private fun uint128Strings() {
    val max = UInt128.MAX_VALUE
    show("MAX toString()",   max.toString())
    show("MAX toString(16)", max.toString(16))
    show("MAX toString(2)",  max.toString(2))

    val parsed = "340282366920938463463374607431768211455".toUInt128()
    show("parsed == MAX_VALUE", parsed == max)

    show("\"-1\".toUInt128OrNull()", "-1".toUInt128OrNull()) // null: unsigned
    show("UInt128.parseStringOrNull(\"oops\", 16)",
         UInt128.parseStringOrNull("oops", 16))
}

// ─── Section 14 ───────────────────────────────────────────────────────────────

private fun uint128CheckedArithmetic() {
    show("(MAX - 1).addExact(1)", (UInt128.MAX_VALUE - UInt128.ONE).addExact(UInt128.ONE))
    try {
        UInt128.MAX_VALUE.addExact(UInt128.ONE)
    } catch (e: ArithmeticException) {
        show("MAX.addExact(1) → throws", e.message)
    }
    try {
        UInt128.ZERO.subtractExact(UInt128.ONE)
    } catch (e: ArithmeticException) {
        show("ZERO.subtractExact(1) → throws", e.message)
    }
}

// ─── Section 15 ───────────────────────────────────────────────────────────────

private fun signedUnsignedInterop() {
    // The bit layouts are identical — switching views never allocates new bits.
    val signed   = Int128.NEGATIVE_ONE
    val unsigned = signed.toUInt128()
    show("Int128(-1) as signed",   signed)   // "-1"
    show("... as UInt128",         unsigned) // 2^128 - 1
    show("round-trip back to Int128", unsigned.toInt128()) // -1 again
}

// ─── Section 16 ───────────────────────────────────────────────────────────────

private fun int128ArrayExamples() {
    // `int128ArrayOf` is the convenience factory; the underlying storage is a
    // flat LongArray of size `2 * n`, so this is a single allocation.
    val arr = int128ArrayOf(
        Int128.fromLong(10L),
        Int128.fromLong(20L),
        Int128.fromLong(30L),
    )
    show("arr",      formatInt128Array(arr))
    show("arr.size", arr.size)
    show("arr[0]",   arr[0])

    // Mutate in place.
    arr[1] = arr[1] * Int128.fromLong(2L)
    show("after arr[1] *= 2", formatInt128Array(arr))

    // Iterate — each element is re-boxed into an `Int128` object, but the
    // storage itself stays flat.
    var total = Int128.ZERO
    for (element in arr) total += element
    show("sum via for-loop", total)

    // Pre-allocate once, fill in a hot loop — the idiomatic pattern for bulk
    // work when you want zero per-element headers.
    val big = Int128Array(size = 8)
    for (i in 0 until big.size) big[i] = Int128.fromLong((i * i).toLong())
    show("i*i array", formatInt128Array(big))
}

private fun formatInt128Array(arr: Int128Array): String = buildString {
    append('[')
    for (i in 0 until arr.size) {
        if (i > 0) append(", ")
        append(arr[i].toString())
    }
    append(']')
}

private fun formatUInt128Array(arr: UInt128Array): String = buildString {
    append('[')
    for (i in 0 until arr.size) {
        if (i > 0) append(", ")
        append(arr[i].toString())
    }
    append(']')
}

// ─── Section 17 ───────────────────────────────────────────────────────────────

private fun uint128ArrayExamples() {
    val arr = uint128ArrayOf(UInt128.ONE, UInt128.fromLong(2L), UInt128.fromLong(3L))
    show("uint arr", formatUInt128Array(arr))

    // Reduce style: multiply them together.
    var product = UInt128.ONE
    for (element in arr) product = product * element
    show("product of arr", product)

    // Demonstrate that equality works element-wise.
    val copy = UInt128Array(arr.size)
    for (i in 0 until arr.size) copy[i] = arr[i]
    show("copy == arr", copy == arr)
}

// ─── Section 18 ───────────────────────────────────────────────────────────────

private fun uuidCaseStudy() {
    // A canonical UUID (RFC 4122) is exactly 128 bits — a perfect UInt128.
    // We parse the hex without the dashes, then render it back.
    val canonical = "550e8400-e29b-41d4-a716-446655440000"
    val uuid = UInt128.parseString(canonical.replace("-", ""), radix = 16)
    show("uuid (decimal)", uuid)
    show("uuid.toString(16)", uuid.toString(16))

    // Reformat with dashes in the RFC 4122 groups: 8-4-4-4-12.
    val hex = uuid.toString(16).padStart(32, '0')
    val formatted = buildString {
        append(hex, 0, 8);   append('-')
        append(hex, 8, 12);  append('-')
        append(hex, 12, 16); append('-')
        append(hex, 16, 20); append('-')
        append(hex, 20, 32)
    }
    show("re-rendered canonical", formatted)
    show("round-trip matches",    formatted == canonical)
}

// ─── Section 19 ───────────────────────────────────────────────────────────────

private fun factorialCaseStudy() {
    // 20! is the largest factorial that fits in a Long. 33! is the largest
    // that fits in a signed Int128 (34! = ~2.95e38 > Int128.MAX_VALUE ≈ 1.70e38).
    // UInt128.MAX_VALUE ≈ 3.40e38 has room for one more: 34!.
    var signed = Int128.ONE
    for (i in 1..33) signed *= Int128.fromLong(i.toLong())
    show("33! (signed)",     signed)
    show("33!.countLeadingZeroBits", signed.countLeadingZeroBits())

    var unsigned = UInt128.ONE
    for (i in 1..34) unsigned *= UInt128.fromLong(i.toLong())
    show("34! (unsigned)",   unsigned)

    // 35! overflows UInt128 → wrapping happens just like a primitive.
    show("35! (wraps)",      unsigned * UInt128.fromLong(35L))
}

// ─── Section 20 ───────────────────────────────────────────────────────────────

private fun runningSumCaseStudy() {
    // Accumulate a million big Long values into an Int128 without overflow.
    val batch = 1_000_000
    var sum = Int128.ZERO
    val each = Int128.fromLong(Long.MAX_VALUE / 2)
    for (i in 0 until batch) sum += each
    show("Σ (Long.MAX/2) for 1M values", sum)
    // Sanity check: same answer computed via multiplication.
    show("same via a * b",              each * Int128.fromLong(batch.toLong()))
}

// ─── Tiny output helpers ──────────────────────────────────────────────────────

private fun section(title: String, body: () -> Unit) {
    println()
    println("══ $title ".padEnd(78, '═'))
    body()
}

private fun show(label: String, value: Any?) {
    val formatted = value?.toString() ?: "null"
    println("  ${label.padEnd(38)} → $formatted")
}
