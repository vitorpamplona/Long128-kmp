
package com.vitorpamplona.long128.internal

import kotlin.native.internal.GCUnsafeCall

/**
 * Native intrinsics for 128-bit arithmetic, backed by C `__int128` code in `int128.def`.
 *
 * ## Why `@GCUnsafeCall` instead of the generated cinterop bindings
 *
 * A normal cinterop call switches the thread's GC state to "native" before the call
 * and back to "runnable" after it, so the garbage collector can run while C code
 * blocks. Each switch is an atomic `xchg`; together they cost ~11 ns, which is
 * 6x the cost of the multiply they wrap. LLVM already inlines the C body (the
 * cinterop C code is linked into the program as bitcode), so that switch is
 * the whole overhead.
 *
 * `@GCUnsafeCall("symbol")` is the annotation the Kotlin/Native stdlib uses for
 * its own small runtime functions: it calls the C symbol directly, without the
 * state switch. LLVM then inlines the C body into the Kotlin caller, and
 * `unsignedMultiplyHigh` compiles to the same single instruction as C:
 *
 * | Operation | x86-64 | ARM64 |
 * |-----------|--------|-------|
 * | unsigned multiply-high | `mul` | `umulh` |
 * | signed multiply-high | `imul` (one-operand form) | `smulh` |
 * | 128÷128 divide | one `call __udivti3` / `__divti3` | same |
 *
 * Verified on the linked benchmark binary in `verify-instructions.sh`.
 *
 * ## Safety contract
 *
 * Skipping the state switch is safe only for C functions that are short, never
 * block, never call back into Kotlin and never touch Kotlin objects: the GC
 * cannot stop this thread while one runs. Every function bound here is a few
 * instructions of integer arithmetic (division is a bounded ~100-cycle
 * `__udivti3` call). Do not bind anything else this way.
 *
 * `GCUnsafeCall` is a Kotlin/Native internal API (opt-in `InternalForKotlinNative`).
 * The klib carries the annotation and the consumer's compiler lowers it when the
 * app is linked, so CI should also build against new Kotlin releases.
 */

@GCUnsafeCall("int128_multiply_high_unsigned")
private external fun cUnsignedMultiplyHigh(a: Long, b: Long): Long

@GCUnsafeCall("int128_multiply_high_signed")
private external fun cSignedMultiplyHigh(a: Long, b: Long): Long

@GCUnsafeCall("uint128_udiv_hi")
private external fun cUDivHi(aHi: Long, aLo: Long, bHi: Long, bLo: Long): Long

@GCUnsafeCall("uint128_udiv_lo")
private external fun cUDivLo(aHi: Long, aLo: Long, bHi: Long, bLo: Long): Long

@GCUnsafeCall("uint128_urem_hi")
private external fun cURemHi(aHi: Long, aLo: Long, bHi: Long, bLo: Long): Long

@GCUnsafeCall("uint128_urem_lo")
private external fun cURemLo(aHi: Long, aLo: Long, bHi: Long, bLo: Long): Long

@GCUnsafeCall("int128_sdiv_hi")
private external fun cSDivHi(aHi: Long, aLo: Long, bHi: Long, bLo: Long): Long

@GCUnsafeCall("int128_sdiv_lo")
private external fun cSDivLo(aHi: Long, aLo: Long, bHi: Long, bLo: Long): Long

@GCUnsafeCall("int128_srem_hi")
private external fun cSRemHi(aHi: Long, aLo: Long, bHi: Long, bLo: Long): Long

@GCUnsafeCall("int128_srem_lo")
private external fun cSRemLo(aHi: Long, aLo: Long, bHi: Long, bLo: Long): Long

internal actual fun unsignedMultiplyHigh(x: Long, y: Long): Long = cUnsignedMultiplyHigh(x, y)

internal actual fun signedMultiplyHigh(x: Long, y: Long): Long = cSignedMultiplyHigh(x, y)

// Four word-sized calls on the same operands: after inlining, LLVM merges them into
// one 128-bit division and derives the remainder from the quotient.

internal actual fun unsignedDivide128(
    dividendHi: Long, dividendLo: Long,
    divisorHi: Long, divisorLo: Long,
): LongArray {
    if (divisorHi == 0L && divisorLo == 0L) throw ArithmeticException("Division by zero")
    return longArrayOf(
        cUDivHi(dividendHi, dividendLo, divisorHi, divisorLo),
        cUDivLo(dividendHi, dividendLo, divisorHi, divisorLo),
        cURemHi(dividendHi, dividendLo, divisorHi, divisorLo),
        cURemLo(dividendHi, dividendLo, divisorHi, divisorLo),
    )
}

internal actual fun signedDivide128(
    dividendHi: Long, dividendLo: Long,
    divisorHi: Long, divisorLo: Long,
): LongArray {
    if (divisorHi == 0L && divisorLo == 0L) throw ArithmeticException("Division by zero")
    if (dividendHi == Long.MIN_VALUE && dividendLo == 0L && divisorHi == -1L && divisorLo == -1L) {
        throw ArithmeticException("Int128 overflow: MIN_VALUE / -1")
    }
    return longArrayOf(
        cSDivHi(dividendHi, dividendLo, divisorHi, divisorLo),
        cSDivLo(dividendHi, dividendLo, divisorHi, divisorLo),
        cSRemHi(dividendHi, dividendLo, divisorHi, divisorLo),
        cSRemLo(dividendHi, dividendLo, divisorHi, divisorLo),
    )
}
