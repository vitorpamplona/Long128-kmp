package com.vitorpamplona.long128.jmh

import com.vitorpamplona.long128.Int128
import com.vitorpamplona.long128.Int128Array
import com.vitorpamplona.long128.MutableInt128
import com.vitorpamplona.long128.MutableUInt128
import com.vitorpamplona.long128.UInt128
import com.vitorpamplona.long128.unsignedMultiplyHigh
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State

/**
 * JMH twins of the loops in `benchmark/benchmark_c.c`: same constants, same dependency
 * chain, same operation count per iteration, so `ns/op` here compares directly with the
 * C output. Each invocation runs [N] iterations and reports per-iteration time.
 *
 * Inputs live in fields so the JIT cannot constant-fold them. (The C versions use
 * compile-time constants, which lets gcc strength-reduce `/ 7` into a multiply; the
 * C benchmark's `*_rt` variants read the divisor at run time for a like-for-like number.)
 */
@State(Scope.Thread)
open class Int128Benchmark {

    // C: make_u128(0x12345678, 0x9ABCDEF0) etc. (each half zero-extended from 32 bits)
    var a = UInt128(0x12345678L, 0x9ABCDEF0L)
    var b = UInt128(0xFEDCBA98L, 0x76543210L)
    var subA = UInt128(0xFFFFFFFFL, 0xFFFFFFFFL)
    var subB = UInt128(1L, 1L)
    var subAcc0 = UInt128(0x7FFFFFFFL, 0xFFFFFFFFL)
    var mulB = UInt128(0L, -0x0123456789ABCDF0L)          // 0xFEDCBA9876543210
    var seven = UInt128(0L, 7L)
    var bigA = UInt128(-1L, -16L)                         // 0xFFFF...FFF0
    var bigB = UInt128(3L, 7L)
    var factor = UInt128(0L, -0x0123456789ABCDF0L)
    var addend = UInt128(0x11L, 0x22L)
    var modulus = UInt128(0L, 1000000007L)
    var sa = Int128(0x12345678L, 0x9ABCDEF0L)
    var sb = Int128(0x12345678L, 0x9ABCDEF1L)
    var sSeven = Int128(0L, 7L)

    var mhA = 0x123456789ABCDEF0L
    var mhB = -0x0123456789ABCDF0L

    lateinit var xs: Int128Array
    lateinit var ys: Int128Array
    lateinit var out: Int128Array

    @Setup
    fun setup() {
        xs = Int128Array(ARRAY)
        ys = Int128Array(ARRAY)
        out = Int128Array(ARRAY)
        for (i in 0 until ARRAY) {
            xs[i] = Int128(i * -0x61C8864680B583EBL + 1, i * -0x61C8864680B583EBL + 2)
            ys[i] = Int128(i * -0x3D4D51C2D82B14B1L + 7, i * -0x3D4D51C2D82B14B1L + 8)
        }
    }

    /** c_add: `acc += a + i; acc += b` — 2 ops per iteration. */
    @Benchmark @OperationsPerInvocation(2 * N)
    fun add(): Long {
        var acc = UInt128.ZERO
        val a = a; val b = b
        for (i in 0 until N) {
            acc += a + UInt128(0L, i.toLong())
            acc += b
        }
        return acc.lo
    }

    /** c_sub: `acc -= b + i; acc += a` — 2 ops per iteration. */
    @Benchmark @OperationsPerInvocation(2 * N)
    fun sub(): Long {
        var acc = subAcc0
        val a = subA; val b = subB
        for (i in 0 until N) {
            acc -= b + UInt128(0L, i.toLong())
            acc += a
        }
        return acc.lo
    }

    /** c_mul: `acc = acc * a + b`. */
    @Benchmark @OperationsPerInvocation(N)
    fun mul(): Long {
        var acc = UInt128.ONE
        val a = a; val b = mulB
        for (i in 0 until N) acc = acc * a + b
        return acc.lo
    }

    /** c_mul_high: `acc += mulhi(a, b); a += 1` on raw 64-bit values. */
    @Benchmark @OperationsPerInvocation(N)
    fun mulHigh(): Long {
        var a = mhA
        val b = mhB
        var acc = 0L
        for (i in 0 until N) {
            acc += unsignedMultiplyHigh(a, b)
            a += 1
        }
        return acc
    }

    /** c_shift: `acc = (acc << 7) ^ (acc >> 3)` (logical shift, unsigned). */
    @Benchmark @OperationsPerInvocation(N)
    fun shift(): Long {
        var acc = a
        for (i in 0 until N) acc = (acc shl 7) xor (acc shr 3)
        return acc.lo
    }

    /** c_compare: `acc += (a < b) ? 1 : -1; a += 1` (signed). */
    @Benchmark @OperationsPerInvocation(N)
    fun compare(): Int {
        var x = sa
        val y = sb
        var acc = 0
        for (i in 0 until N) {
            acc += if (x < y) 1 else -1
            x += Int128.ONE
        }
        return acc
    }

    /** c_div_by_small: `acc = acc / b + a`, b = 7 (unsigned). */
    @Benchmark @OperationsPerInvocation(N_DIV)
    fun divSmall(): Long {
        var acc = a
        val a = a; val d = seven
        for (i in 0 until N_DIV) acc = acc / d + a
        return acc.lo
    }

    /** c_div_by_large: `acc = acc / b + a`, 128-bit divisor (unsigned). */
    @Benchmark @OperationsPerInvocation(N_DIV)
    fun divLarge(): Long {
        var acc = bigA
        val a = bigA; val d = bigB
        for (i in 0 until N_DIV) acc = acc / d + a
        return acc.lo
    }

    /** Signed counterpart of [divSmall], through Int128. */
    @Benchmark @OperationsPerInvocation(N_DIV)
    fun divSmallSigned(): Long {
        var acc = sa
        val a = sa; val d = sSeven
        for (i in 0 until N_DIV) acc = acc / d + a
        return acc.lo
    }

    /** c_mixed: `acc = acc * factor + addend`, and `acc %= modulus` every 256 iterations. */
    @Benchmark @OperationsPerInvocation(N)
    fun mixed(): Long {
        var acc = UInt128.ONE
        val f = factor; val ad = addend; val m = modulus
        for (i in 0 until N) {
            acc = acc * f + ad
            if ((i and 0xFF) == 0) acc %= m
        }
        return acc.lo
    }

    /** c_array_mul: `out[i] = x[i] * y[i]` over 1024 values (throughput, no dependency chain). */
    @Benchmark @OperationsPerInvocation(ARRAY)
    fun arrayMul(): Long {
        val x = xs; val y = ys; val o = out
        for (i in 0 until ARRAY) o[i] = x[i] * y[i]
        return o[ARRAY - 1].lo
    }

    // ── Same loops with a MutableUInt128 / MutableInt128 accumulator ───────────────
    // Allocated once per invocation; escape analysis keeps its fields in registers.

    @Benchmark @OperationsPerInvocation(2 * N)
    fun addMutable(): Long {
        val acc = MutableUInt128()
        val a = a; val b = b
        for (i in 0 until N) {
            acc += a + UInt128(0L, i.toLong())
            acc += b
        }
        return acc.lo
    }

    @Benchmark @OperationsPerInvocation(2 * N)
    fun subMutable(): Long {
        val acc = MutableUInt128(subAcc0)
        val a = subA; val b = subB
        for (i in 0 until N) {
            acc -= b + UInt128(0L, i.toLong())
            acc += a
        }
        return acc.lo
    }

    @Benchmark @OperationsPerInvocation(N)
    fun mulMutable(): Long {
        val acc = MutableUInt128(UInt128.ONE)
        val a = a; val b = mulB
        for (i in 0 until N) { acc *= a; acc += b }
        return acc.lo
    }

    @Benchmark @OperationsPerInvocation(N)
    fun shiftMutable(): Long {
        val acc = MutableUInt128(a)
        val t = MutableUInt128()
        for (i in 0 until N) {
            t.set(acc.hi, acc.lo); t.shiftRight(3)      // t = acc >> 3
            acc.shiftLeft(7)                            // acc = acc << 7
            acc.xorAssign(t.toUInt128())                // acc ^= t
        }
        return acc.lo
    }

    @Benchmark @OperationsPerInvocation(N)
    fun compareMutable(): Int {
        val x = MutableInt128(sa)
        val y = sb
        var acc = 0
        for (i in 0 until N) {
            acc += if (x < y) 1 else -1
            x += Int128.ONE
        }
        return acc
    }

    @Benchmark @OperationsPerInvocation(N_DIV)
    fun divSmallMutable(): Long {
        val acc = MutableUInt128(a)
        val a = a; val d = seven
        for (i in 0 until N_DIV) { acc /= d; acc += a }
        return acc.lo
    }

    @Benchmark @OperationsPerInvocation(N_DIV)
    fun divLargeMutable(): Long {
        val acc = MutableUInt128(bigA)
        val a = bigA; val d = bigB
        for (i in 0 until N_DIV) { acc /= d; acc += a }
        return acc.lo
    }

    @Benchmark @OperationsPerInvocation(N)
    fun mixedMutable(): Long {
        val acc = MutableUInt128(UInt128.ONE)
        val f = factor; val ad = addend; val m = modulus
        for (i in 0 until N) {
            acc.multiplyAdd(f, ad)
            if ((i and 0xFF) == 0) acc %= m
        }
        return acc.lo
    }

    /** The JVM floor: the multiply-add chain on two raw `long`s, no library types at all. */
    @Benchmark @OperationsPerInvocation(N)
    fun mulRawLongs(): Long {
        var hi = 0L; var lo = 1L
        val aHi = a.hi; val aLo = a.lo; val bHi = mulB.hi; val bLo = mulB.lo
        for (i in 0 until N) {
            val pHi = unsignedMultiplyHigh(lo, aLo) + hi * aLo + lo * aHi
            val pLo = lo * aLo
            val sLo = pLo + bLo
            hi = pHi + bHi + (if ((sLo xor Long.MIN_VALUE) < (pLo xor Long.MIN_VALUE)) 1L else 0L)
            lo = sLo
        }
        return lo
    }

    companion object {
        const val N = 10_000
        const val N_DIV = 1_000
        const val ARRAY = 1024
    }
}
