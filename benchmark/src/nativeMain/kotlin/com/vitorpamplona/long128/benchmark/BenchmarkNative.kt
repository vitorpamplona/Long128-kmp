package com.vitorpamplona.long128.benchmark

/**
 * Kotlin/Native benchmark for 128-bit arithmetic vs C __int128.
 *
 * Mirrors BenchmarkJvm.kt operation for operation, with the same output labels,
 * so run-benchmarks.sh can put C, JVM and Native side by side. The scalar tier
 * calls the library's public [unsignedMultiplyHigh], which on native lowers to a
 * single `mul` (x86-64) / `umulh` (ARM64) inlined into the loop.
 *
 * Run: ./gradlew -q :benchmark:runReleaseExecutableLinuxX64   (or MacosArm64 / MacosX64)
 */

import com.vitorpamplona.long128.*
import kotlin.experimental.ExperimentalNativeApi
import kotlin.time.TimeSource

var sink: Long = 0L

fun elapsed(start: TimeSource.Monotonic.ValueTimeMark): Double = start.elapsedNow().inWholeNanoseconds.toDouble()

@OptIn(ExperimentalNativeApi::class)
fun main() {
    val N = 10_000_000
    val N_DIV = 1_000_000

    // AOT-compiled: no JIT warmup needed.
    println("Kotlin Int128 Native benchmark ($N iterations, ${Platform.osFamily} ${Platform.cpuArchitecture})")
    println()

    println("── Scalar (raw Longs, simulates MFVC — no allocation) ──")
    scalarAdd(N)
    scalarSub(N)
    scalarMul(N)
    scalarMulHigh(N)
    scalarShift(N)
    scalarCompare(N)
    println()

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

    println("── Int128Array (flat array — no per-element allocation) ─")
    arrayAdd(N)
    arrayMul(N)
    println()
    if (sink == 42L) println()  // keeps every result observable
}

fun scalarAdd(iterations: Int) {
    // 128-bit add using two Longs — same carry pattern as Int128.plus
    val aHi = 0x12345678L; val aLo = -0x6543210FL // 0x9ABCDEF1
    val bHi = -0x01234568L; val bLo = 0x76543210L // 0xFEDCBA98...
    var accHi = 0L; var accLo = 0L

    val start = TimeSource.Monotonic.markNow()
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

    val start = TimeSource.Monotonic.markNow()
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

    val start = TimeSource.Monotonic.markNow()
    for (i in 0 until iterations) {
        // acc = acc * a + b (using the library's unsignedMultiplyHigh)
        val prodLo = accLo * aLo
        val prodHi = unsignedMultiplyHigh(accLo, aLo) + accHi * aLo + accLo * aHi
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

    val start = TimeSource.Monotonic.markNow()
    for (i in 0 until iterations) {
        acc += unsignedMultiplyHigh(a, b)
        a += 1
    }
    val ns = elapsed(start) / iterations
    sink = acc
    printf("  scalar_mul_high: %8.2f ns/op\n", ns)
}

fun scalarShift(iterations: Int) {
    var hi = 0x12345678L; var lo = -0x6543210FL

    val start = TimeSource.Monotonic.markNow()
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

    val start = TimeSource.Monotonic.markNow()
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

    val start = TimeSource.Monotonic.markNow()
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

    val start = TimeSource.Monotonic.markNow()
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

    val start = TimeSource.Monotonic.markNow()
    for (i in 0 until iterations) {
        acc = acc * a + b
    }
    val ns = elapsed(start) / iterations
    sink = acc.lo
    printf("  int128_mul:      %8.2f ns/op\n", ns)
}

fun int128Shift(iterations: Int) {
    var acc = Int128(0x12345678L, -0x6543210FL)

    val start = TimeSource.Monotonic.markNow()
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

    val start = TimeSource.Monotonic.markNow()
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

    val start = TimeSource.Monotonic.markNow()
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

    val start = TimeSource.Monotonic.markNow()
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

    val start = TimeSource.Monotonic.markNow()
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

    val start = TimeSource.Monotonic.markNow()
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

    val start = TimeSource.Monotonic.markNow()
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

/** Minimal `%8.2f` formatter: Kotlin/Native has no String.format. */
fun printf(fmt: String, ns: Double) {
    val rounded = (ns * 100 + 0.5).toLong()
    val text = "${rounded / 100}.${(rounded % 100).toString().padStart(2, '0')}"
    print(fmt.replace("%8.2f", text.padStart(8)))
}
