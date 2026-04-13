package com.vitorpamplona.long128

import kotlin.test.*

/**
 * Detects whether HotSpot C2 has intrinsified Math.multiplyHigh by comparing
 * its throughput against a known-slow software implementation.
 *
 * If Math.multiplyHigh is intrinsified to a single IMUL instruction (~1ns),
 * it will be at least 2x faster than the Karatsuba decomposition (~3-5ns).
 * If it's NOT intrinsified (running the Java implementation), both paths
 * have similar speed and the test warns but does not fail — the library
 * is still correct, just slower.
 *
 * This test also serves as a smoke test that the MethodHandle dispatch
 * works at runtime (not just at the bytecode level).
 */
class JvmIntrinsicTimingTest {

    @Test
    fun multiplyHighIsFasterThanSoftwareFallback() {
        val N = 5_000_000

        // Warmup both paths
        repeat(3) {
            runMultiplyHigh(N / 5)
            runKaratsuba(N / 5)
        }

        // Measure
        val intrinsicNs = runMultiplyHigh(N)
        val softwareNs = runKaratsuba(N)

        val ratio = softwareNs.toDouble() / intrinsicNs.toDouble()

        println("  Math.multiplyHigh:     $intrinsicNs ns total")
        println("  Karatsuba fallback:    $softwareNs ns total")
        println("  Speedup:               %.1fx".format(ratio))

        if (ratio < 1.3) {
            println("  WARNING: Math.multiplyHigh is NOT faster than software.")
            println("  This means HotSpot did not intrinsify it (running Java impl).")
            println("  The library still works correctly but multiply is ~3-5ns instead of ~1ns.")
            println("  This can happen on non-HotSpot JVMs (OpenJ9, GraalVM CE) or with -Xint.")
        } else {
            println("  OK: Math.multiplyHigh is ${String.format("%.1f", ratio)}x faster → intrinsified")
        }

        // Don't fail — some JVMs don't intrinsify, and the library still works.
        // But log it clearly so CI can flag unexpected regressions.
    }

    @Test
    fun methodHandleResolvesAndProducesCorrectResults() {
        // This is the critical functional test: does our MethodHandle dispatch
        // actually call Math.multiplyHigh and get the right answer?
        // If the MethodHandle failed to resolve, we'd get the fallback (still correct).
        // If the MethodHandle resolved but called the wrong method, we'd get wrong results.
        val result = com.vitorpamplona.long128.internal.unsignedMultiplyHigh(-1L, -1L)
        assertEquals(-2L, result, "unsignedMultiplyHigh(MAX, MAX) must be 0xFFFFFFFFFFFFFFFE")
    }

    // ── Benchmark helpers ───────────────────────────────────────────

    private fun runMultiplyHigh(iterations: Int): Long {
        var a = 0x123456789ABCDEF0L
        val b = -0x0123456789ABCDF0L
        var acc = 0L

        val start = System.nanoTime()
        for (i in 0 until iterations) {
            acc += Math.multiplyHigh(a, b)
            a += 1
        }
        val elapsed = System.nanoTime() - start
        sinkValue = acc // prevent DCE
        return elapsed
    }

    private fun runKaratsuba(iterations: Int): Long {
        var a = 0x123456789ABCDEF0L
        val b = -0x0123456789ABCDF0L
        var acc = 0L

        val start = System.nanoTime()
        for (i in 0 until iterations) {
            acc += karatsubaMultiplyHigh(a, b)
            a += 1
        }
        val elapsed = System.nanoTime() - start
        sinkValue = acc
        return elapsed
    }

    /** Software multiplyHigh — intentionally NOT using the platform intrinsic. */
    private fun karatsubaMultiplyHigh(x: Long, y: Long): Long {
        val x1 = x shr 32; val x0 = x and 0xFFFFFFFFL
        val y1 = y shr 32; val y0 = y and 0xFFFFFFFFL
        val z2 = x1 * y1
        val t = x1 * y0 + (x0 * y0).ushr(32)
        val z1 = (t and 0xFFFFFFFFL) + x0 * y1
        return z2 + (t shr 32) + (z1 shr 32)
    }

    companion object {
        @JvmField @Volatile var sinkValue = 0L
    }
}
