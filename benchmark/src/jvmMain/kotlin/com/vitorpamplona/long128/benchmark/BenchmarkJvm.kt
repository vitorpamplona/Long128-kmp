package com.vitorpamplona.long128.benchmark

/**
 * Kotlin JVM benchmark for 128-bit arithmetic.
 *
 * Mirrors every operation from benchmark_c.c so results are directly comparable.
 * Uses the same iteration counts, operand values, and accumulation patterns.
 *
 * Compile & run:
 *   ./gradlew :long128-core:jvmJar
 *   kotlinc -cp long128-core/build/libs/long128-core-jvm-*.jar -include-runtime \
 *           -d benchmark/benchmark_jvm.jar benchmark/BenchmarkJvm.kt
 *   java -jar benchmark/benchmark_jvm.jar
 *
 * Or via the runner script:
 *   ./benchmark/run-benchmarks.sh
 */

import com.vitorpamplona.long128.*

/* Volatile sink — prevents HotSpot from eliminating dead computation.
 * @JvmField + @Volatile ensures this is a real volatile field, not a
 * property with getter/setter overhead. */
@JvmField @Volatile var sink: Long = 0L

fun elapsed(startNs: Long): Double = (System.nanoTime() - startNs).toDouble()

fun main() {
    val N = 10_000_000  // 10M iterations, matches C benchmark
    val N_DIV = 1_000_000 // 1M for division

    // Warmup — let HotSpot C2 compile the hot paths
    println("Warming up JIT...")
    benchAdd(N / 10)
    benchMul(N / 10)
    benchDivSmall(N_DIV / 10)
    benchMixed(N / 10)
    println()

    println("Kotlin Int128 JVM benchmark ($N iterations, JDK ${Runtime.version().feature()})")
    println("─────────────────────────────────────────────")

    benchAdd(N)
    benchSub(N)
    benchMul(N)
    benchMulHigh(N)
    benchShift(N)
    benchCompare(N)
    benchDivSmall(N_DIV)
    benchDivLarge(N_DIV)
    benchMixed(N)

    println("─────────────────────────────────────────────")
}

fun benchAdd(iterations: Int) {
    val a = Int128(0x12345678L, 0x9ABCDEF0L.toLong())
    val b = Int128(0xFEDCBA98L.toLong(), 0x76543210L)
    var acc = Int128.ZERO

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        acc = acc + a + Int128.fromLong(i.toLong()) // data dependency on i
        acc = acc + b
    }
    val ns = elapsed(start) / (iterations * 2)
    sink = acc.lo
    printf("  kt_add:          %8.2f ns/op\n", ns)
}

fun benchSub(iterations: Int) {
    val a = Int128(-1L, -1L)
    val b = Int128(0x00000001L, 0x00000001L)
    var acc = Int128(0x7FFFFFFFL, -1L)

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        acc = acc - b - Int128.fromLong(i.toLong()) // data dependency on i
        acc = acc + a
    }
    val ns = elapsed(start) / (iterations * 2)
    sink = acc.lo
    printf("  kt_sub:          %8.2f ns/op\n", ns)
}

fun benchMul(iterations: Int) {
    val a = Int128(0x12345678L, 0x9ABCDEF0L.toLong())
    val b = Int128(0x00000000L, -0x0123456789ABCDF0L) // 0xFEDCBA9876543210
    var acc = Int128.ONE

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        acc = acc * a + b
    }
    val ns = elapsed(start) / iterations
    sink = acc.lo
    printf("  kt_mul:          %8.2f ns/op\n", ns)
}

fun benchMulHigh(iterations: Int) {
    var a = 0x123456789ABCDEF0L
    val b = -0x0123456789ABCDF0L // 0xFEDCBA9876543210
    var acc = 0L

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        // Mirrors what unsignedMultiplyHigh does: Math.multiplyHigh + unsigned correction
        val signed = Math.multiplyHigh(a, b)
        acc += signed + (a and (b shr 63)) + (b and (a shr 63))
        a += 1
    }
    val ns = elapsed(start) / iterations
    sink = acc
    printf("  kt_mul_high:     %8.2f ns/op\n", ns)
}

fun benchShift(iterations: Int) {
    val a = Int128(0x12345678L, 0x9ABCDEF0L.toLong())
    var acc = a

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        acc = (acc shl 7) xor (acc ushr 3)
    }
    val ns = elapsed(start) / iterations
    sink = acc.lo
    printf("  kt_shift:        %8.2f ns/op\n", ns)
}

fun benchCompare(iterations: Int) {
    var a = Int128(0x12345678L, 0x9ABCDEF0L.toLong())
    val b = Int128(0x12345678L, 0x9ABCDEF1L.toLong())
    var acc = 0

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        acc += if (a < b) 1 else -1
        a = a + Int128.ONE
    }
    val ns = elapsed(start) / iterations
    sink = acc.toLong()
    printf("  kt_compare:      %8.2f ns/op\n", ns)
}

fun benchDivSmall(iterations: Int) {
    val a = Int128(0x12345678L, 0x9ABCDEF0L.toLong())
    val b = Int128(0x00000000L, 0x0000000000000007L)
    var acc = a

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        acc = acc / b + a
    }
    val ns = elapsed(start) / iterations
    sink = acc.lo
    printf("  kt_div_by_small: %8.2f ns/op\n", ns)
}

fun benchDivLarge(iterations: Int) {
    val a = Int128(-1L, -16L) // 0xFFF...FFF0
    val b = Int128(0x0000000000000003L, 0x0000000000000007L)
    var acc = a

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        acc = acc / b + a
    }
    val ns = elapsed(start) / iterations
    sink = acc.lo
    printf("  kt_div_by_large: %8.2f ns/op\n", ns)
}

fun benchMixed(iterations: Int) {
    var acc = Int128.ONE
    val factor = Int128(0L, -0x0123456789ABCDF0L)
    val addend = Int128(0x11L, 0x22L)
    val divisor = Int128(0L, 1000000007L)

    val start = System.nanoTime()
    for (i in 0 until iterations) {
        acc = acc * factor + addend
        if ((i and 0xFF) == 0) {
            acc = acc % divisor
        }
    }
    val ns = elapsed(start) / iterations
    sink = acc.lo
    printf("  kt_mixed:        %8.2f ns/op\n", ns)
}

fun printf(fmt: String, vararg args: Any) {
    print(String.format(fmt, *args))
}
