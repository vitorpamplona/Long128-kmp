package com.vitorpamplona.long128.benchmark

/**
 * Kotlin JVM benchmark for 128-bit arithmetic vs C __int128.
 *
 * Three tiers of measurement, matching the same operations as benchmark_c.c:
 *
 * 1. **Scalar (raw Longs)**: Simulates what Kotlin MFVC would give us — two Long
 *    variables, no object allocation. This is the theoretical best Kotlin can do.
 *    Measures pure instruction overhead (MethodHandle dispatch, carry detection).
 *
 * 2. **Int128 (heap objects)**: The real library API. Measures instruction overhead
 *    PLUS object allocation per result. This is what users experience today.
 *
 * 3. **Int128Array (flat array)**: Batch operations on a pre-allocated array.
 *    No per-operation allocation. Measures instruction overhead + array access.
 *
 * Run: ./gradlew -q :benchmark:jvmRun
 */

import com.vitorpamplona.long128.*

@JvmField @Volatile var sink: Long = 0L

fun elapsed(startNs: Long): Double = (System.nanoTime() - startNs).toDouble()

fun main() {
    val N = 10_000_000
    val N_DIV = 1_000_000

    // Warmup
    println("Warming up JIT (3 rounds)...")
    repeat(3) {
        scalarAdd(N / 5)
        scalarMul(N / 5)
        int128Add(N / 5)
        int128Mul(N / 5)
        int128DivSmall(N_DIV / 5)
    }
    println()

    println("Kotlin Int128 JVM benchmark ($N iterations, JDK ${Runtime.version().feature()})")
    println()

    // ── Tier 1: Scalar (raw Longs, no allocation) ────────────────
    println("── Scalar (raw Longs, simulates MFVC — no allocation) ──")
    scalarAdd(N)
    scalarSub(N)
    scalarMul(N)
    scalarMulHigh(N)
    scalarShift(N)
    scalarCompare(N)
    println()

    // ── Tier 2: Int128 (heap objects) ────────────────────────────
    println("── Int128 (heap objects — what users get today) ────────")
    int128Add(N)
    int128Sub(N)
    int128Mul(N)
    int128Shift(N)
    int128Compare(N)
    int128DivSmall(N_DIV)
    int128DivLarge(N_DIV)
    int128Mixed(N)
    println()

    // ── Tier 3: Int128Array (flat backing, no per-op allocation) ─
    println("── Int128Array (flat array — no per-element allocation) ─")
    arrayAdd(N)
    arrayMul(N)
    println()
}

// ═══════════════════════════════════════════════════════════════════
// Tier 1: Scalar — raw Long pairs, no object allocation.
// This is the instruction cost without JVM object overhead.
// Directly comparable to C __int128.
// ═══════════════════════════════════════════════════════════════════

fun scalarAdd(iterations: Int) {
    // 128-bit add using two Longs — same carry pattern as Int128.plus
    val aHi = 0x12345678L; val aLo = -0x6543210FL // 0x9ABCDEF1
    val bHi = -0x01234568L; val bLo = 0x76543210L // 0xFEDCBA98...
    var accHi = 0L; var accLo = 0L

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        // acc += a
        var lo = accLo + aLo
        var carry = if (lo.toULong() < accLo.toULong()) 1L else 0L
        accHi = accHi + aHi + carry
        accLo = lo
        // acc += b
        lo = accLo + bLo
        carry = if (lo.toULong() < accLo.toULong()) 1L else 0L
        accHi = accHi + bHi + carry
        accLo = lo
    }
    val ns = elapsed(start) / (iterations * 2)
    sink = accLo
    printf("  scalar_add:      %8.2f ns/op\n", ns)
}

fun scalarSub(iterations: Int) {
    val bHi = 0x00000001L; val bLo = 0x00000001L
    val aHi = -1L; val aLo = -1L
    var accHi = 0x7FFFFFFFL; var accLo = -1L

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        // acc -= b
        var lo = accLo - bLo
        var borrow = if (accLo.toULong() < bLo.toULong()) 1L else 0L
        accHi = accHi - bHi - borrow
        accLo = lo
        // acc += a
        lo = accLo + aLo
        val carry = if (lo.toULong() < accLo.toULong()) 1L else 0L
        accHi = accHi + aHi + carry
        accLo = lo
    }
    val ns = elapsed(start) / (iterations * 2)
    sink = accLo
    printf("  scalar_sub:      %8.2f ns/op\n", ns)
}

fun scalarMul(iterations: Int) {
    val aHi = 0x12345678L; val aLo = -0x6543210FL
    val bHi = 0L; val bLo = -0x0123456789ABCDF0L
    var accHi = 0L; var accLo = 1L

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        // acc = acc * a + b (using unsignedMultiplyHigh via Math.multiplyHigh)
        val prodLo = accLo * aLo
        val signed = Math.multiplyHigh(accLo, aLo)
        val prodHi = signed + (accLo and (aLo shr 63)) + (aLo and (accLo shr 63)) +
            accHi * aLo + accLo * aHi
        // + b
        val sumLo = prodLo + bLo
        val carry = if (sumLo.toULong() < prodLo.toULong()) 1L else 0L
        accHi = prodHi + bHi + carry
        accLo = sumLo
    }
    val ns = elapsed(start) / iterations
    sink = accLo
    printf("  scalar_mul:      %8.2f ns/op\n", ns)
}

fun scalarMulHigh(iterations: Int) {
    var a = 0x123456789ABCDEF0L
    val b = -0x0123456789ABCDF0L
    var acc = 0L

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        val signed = Math.multiplyHigh(a, b)
        acc += signed + (a and (b shr 63)) + (b and (a shr 63))
        a += 1
    }
    val ns = elapsed(start) / iterations
    sink = acc
    printf("  scalar_mul_high: %8.2f ns/op\n", ns)
}

fun scalarShift(iterations: Int) {
    var hi = 0x12345678L; var lo = -0x6543210FL

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        // (hi,lo) = ((hi,lo) << 7) ^ ((hi,lo) >>> 3)
        val shlHi = (hi shl 7) or (lo ushr 57)
        val shlLo = lo shl 7
        val shrHi = hi ushr 3
        val shrLo = (lo ushr 3) or (hi shl 61)
        hi = shlHi xor shrHi
        lo = shlLo xor shrLo
    }
    val ns = elapsed(start) / iterations
    sink = lo
    printf("  scalar_shift:    %8.2f ns/op\n", ns)
}

fun scalarCompare(iterations: Int) {
    var aHi = 0x12345678L; var aLo = -0x6543210FL
    val bHi = 0x12345678L; val bLo = -0x6543210EL
    var acc = 0

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        // Signed 128-bit compare (same logic as Int128.compareTo)
        val cmp = if (aHi != bHi) aHi.compareTo(bHi) else aLo.toULong().compareTo(bLo.toULong())
        acc += if (cmp < 0) 1 else -1
        aLo += 1
        if (aLo == 0L) aHi += 1  // carry
    }
    val ns = elapsed(start) / iterations
    sink = acc.toLong()
    printf("  scalar_compare:  %8.2f ns/op\n", ns)
}

// ═══════════════════════════════════════════════════════════════════
// Tier 2: Int128 — the real library API.
// Measures instruction + object allocation cost.
// ═══════════════════════════════════════════════════════════════════

fun int128Add(iterations: Int) {
    val a = Int128(0x12345678L, -0x6543210FL)
    val b = Int128(-0x01234568L, 0x76543210L)
    var acc = Int128.ZERO

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        acc = acc + a  // allocates new Int128
        acc = acc + b  // allocates new Int128
    }
    val ns = elapsed(start) / (iterations * 2)
    sink = acc.lo
    printf("  int128_add:      %8.2f ns/op\n", ns)
}

fun int128Sub(iterations: Int) {
    val a = Int128(-1L, -1L)
    val b = Int128(0x00000001L, 0x00000001L)
    var acc = Int128(0x7FFFFFFFL, -1L)

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        acc = acc - b
        acc = acc + a
    }
    val ns = elapsed(start) / (iterations * 2)
    sink = acc.lo
    printf("  int128_sub:      %8.2f ns/op\n", ns)
}

fun int128Mul(iterations: Int) {
    val a = Int128(0x12345678L, -0x6543210FL)
    val b = Int128(0L, -0x0123456789ABCDF0L)
    var acc = Int128.ONE

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        acc = acc * a + b
    }
    val ns = elapsed(start) / iterations
    sink = acc.lo
    printf("  int128_mul:      %8.2f ns/op\n", ns)
}

fun int128Shift(iterations: Int) {
    var acc = Int128(0x12345678L, -0x6543210FL)

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        acc = (acc shl 7) xor (acc ushr 3)
    }
    val ns = elapsed(start) / iterations
    sink = acc.lo
    printf("  int128_shift:    %8.2f ns/op\n", ns)
}

fun int128Compare(iterations: Int) {
    // Pure comparison — no add inside the loop.
    // Pre-create two arrays of values to compare.
    val n = minOf(iterations, 1_000_000)
    val valuesA = Int128Array(n)
    val valuesB = Int128Array(n)
    for (i in 0 until n) {
        valuesA[i] = Int128(0x12345678L, i.toLong())
        valuesB[i] = Int128(0x12345678L, i.toLong() + 1)
    }
    var acc = 0

    val start = System.nanoTime()
    val rounds = iterations / n
    for (r in 0 until rounds) {
        for (i in 0 until n) {
            acc += if (valuesA[i] < valuesB[i]) 1 else -1
        }
    }
    val ns = elapsed(start) / iterations
    sink = acc.toLong()
    printf("  int128_compare:  %8.2f ns/op\n", ns)
}

fun int128DivSmall(iterations: Int) {
    val a = Int128(0x12345678L, -0x6543210FL)
    val b = Int128(0L, 7L)
    var acc = a

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        acc = acc / b + a
    }
    val ns = elapsed(start) / iterations
    sink = acc.lo
    printf("  int128_div_sm:   %8.2f ns/op\n", ns)
}

fun int128DivLarge(iterations: Int) {
    val a = Int128(-1L, -16L)
    val b = Int128(3L, 7L)
    var acc = a

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        acc = acc / b + a
    }
    val ns = elapsed(start) / iterations
    sink = acc.lo
    printf("  int128_div_lg:   %8.2f ns/op\n", ns)
}

fun int128Mixed(iterations: Int) {
    var acc = Int128.ONE
    val factor = Int128(0L, -0x0123456789ABCDF0L)
    val addend = Int128(0x11L, 0x22L)
    val divisor = Int128(0L, 1000000007L)

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        acc = acc * factor + addend
        if ((i and 0xFF) == 0) acc = acc % divisor
    }
    val ns = elapsed(start) / iterations
    sink = acc.lo
    printf("  int128_mixed:    %8.2f ns/op\n", ns)
}

// ═══════════════════════════════════════════════════════════════════
// Tier 3: Int128Array — flat LongArray backing, no per-op allocation.
// Shows the benefit of avoiding heap objects in bulk operations.
// ═══════════════════════════════════════════════════════════════════

fun arrayAdd(iterations: Int) {
    val n = minOf(iterations, 1_000_000)
    val a = Int128Array(n)
    val b = Int128Array(n)
    val result = Int128Array(n)
    for (i in 0 until n) {
        a[i] = Int128(0x12345678L, i.toLong())
        b[i] = Int128(-0x01234568L, 0x76543210L + i.toLong())
    }

    val start = System.nanoTime()
    val rounds = iterations / n
    for (r in 0 until rounds) {
        for (i in 0 until n) {
            result[i] = a[i] + b[i]
        }
    }
    val ns = elapsed(start) / iterations
    sink = result[0].lo
    printf("  array_add:       %8.2f ns/op\n", ns)
}

fun arrayMul(iterations: Int) {
    val n = minOf(iterations, 1_000_000)
    val a = Int128Array(n)
    val b = Int128Array(n)
    val result = Int128Array(n)
    for (i in 0 until n) {
        a[i] = Int128(0L, 0x12345678L + i.toLong())
        b[i] = Int128(0L, -0x0123456789ABCDF0L + i.toLong())
    }

    val start = System.nanoTime()
    val rounds = iterations / n
    for (r in 0 until rounds) {
        for (i in 0 until n) {
            result[i] = a[i] * b[i]
        }
    }
    val ns = elapsed(start) / iterations
    sink = result[0].lo
    printf("  array_mul:       %8.2f ns/op\n", ns)
}

fun printf(fmt: String, vararg args: Any) {
    print(String.format(fmt, *args))
}
