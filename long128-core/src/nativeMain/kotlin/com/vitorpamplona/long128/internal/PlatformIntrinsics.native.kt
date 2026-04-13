package com.vitorpamplona.long128.internal

import int128ops.*
import kotlinx.cinterop.*

/**
 * Native intrinsics for 128-bit arithmetic via C interop with `__int128`.
 *
 * Each function delegates to a C function compiled from `__int128` /
 * `unsigned __int128` types defined in `int128.def`. Clang compiles these
 * to the optimal hardware instruction for each target architecture:
 *
 * | Operation | x86-64 | ARM64 |
 * |-----------|--------|-------|
 * | multiply-high | `mul rsi` (1 insn) | `umulh` (1 insn) |
 * | full 128×128 mul | `imul`+`imul`+`mul`+`add`+`add` (5 insns) | `umulh`+`madd`+`madd`+`mul` (4 insns) |
 * | divide | `call __divti3` (optimized runtime) | same |
 *
 * Verified via objdump (x86-64) and clang cross-compilation (ARM64) in
 * `verify-instructions.sh`.
 *
 * ## ABI portability
 *
 * Multi-word return values use **out-pointer parameters** instead of struct
 * returns. This avoids calling-convention differences:
 * - System V (Linux/macOS): would return 16-byte structs in RAX:RDX
 * - Windows x64 (mingwX64): returns structs > 8 bytes via hidden pointer
 *
 * Out-parameters work identically on both ABIs and on every cinterop target.
 *
 * ## Why add/sub stay in pure Kotlin
 *
 * cinterop calls have ~10-20ns overhead (GC safepoint). For add/sub the
 * actual work is ~2ns, so cinterop would make them slower. The Kotlin
 * carry-detection pattern `(result.toULong() < operand.toULong())` is
 * recognized by both GCC and Clang as a carry-flag test and compiles to
 * `add`+`adc` / `adds`+`adc` — the same instructions as the `__int128`
 * version (verified in `verify-instructions.sh`).
 */

internal actual fun unsignedMultiplyHigh(x: Long, y: Long): Long =
    int128_multiply_high_unsigned(x, y)

internal actual fun multiply128(aHi: Long, aLo: Long, bHi: Long, bLo: Long): LongArray =
    memScoped {
        val outHi = alloc<LongVar>()
        val outLo = alloc<LongVar>()
        int128_mul(aHi, aLo, bHi, bLo, outHi.ptr, outLo.ptr)
        longArrayOf(outHi.value, outLo.value)
    }

internal actual fun unsignedDivide128(
    dividendHi: Long, dividendLo: Long,
    divisorHi: Long, divisorLo: Long,
): LongArray {
    if (divisorHi == 0L && divisorLo == 0L) throw ArithmeticException("Division by zero")
    return memScoped {
        val qHi = alloc<LongVar>()
        val qLo = alloc<LongVar>()
        val rHi = alloc<LongVar>()
        val rLo = alloc<LongVar>()
        uint128_udivrem(dividendHi, dividendLo, divisorHi, divisorLo,
            qHi.ptr, qLo.ptr, rHi.ptr, rLo.ptr)
        longArrayOf(qHi.value, qLo.value, rHi.value, rLo.value)
    }
}

internal actual fun signedDivide128(
    dividendHi: Long, dividendLo: Long,
    divisorHi: Long, divisorLo: Long,
): LongArray {
    if (divisorHi == 0L && divisorLo == 0L) throw ArithmeticException("Division by zero")
    if (dividendHi == Long.MIN_VALUE && dividendLo == 0L && divisorHi == -1L && divisorLo == -1L) {
        throw ArithmeticException("Int128 overflow: MIN_VALUE / -1")
    }
    return memScoped {
        val qHi = alloc<LongVar>()
        val qLo = alloc<LongVar>()
        val rHi = alloc<LongVar>()
        val rLo = alloc<LongVar>()
        int128_sdivrem(dividendHi, dividendLo, divisorHi, divisorLo,
            qHi.ptr, qLo.ptr, rHi.ptr, rLo.ptr)
        longArrayOf(qHi.value, qLo.value, rHi.value, rLo.value)
    }
}
