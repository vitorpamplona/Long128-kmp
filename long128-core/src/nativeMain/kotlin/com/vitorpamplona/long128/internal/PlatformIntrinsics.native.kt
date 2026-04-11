package com.vitorpamplona.long128.internal

import int128ops.*
import kotlinx.cinterop.useContents

/**
 * Native intrinsics for 128-bit arithmetic via C interop with `__int128`.
 *
 * Each function delegates to a C function compiled from `__int128` / `unsigned __int128`
 * types (defined in `int128.def`). Clang compiles these to the optimal hardware
 * instruction for each target architecture:
 *
 * | Operation | x86-64 | ARM64 |
 * |-----------|--------|-------|
 * | multiply-high | `mul rsi` (1 insn) | `umulh` (1 insn) |
 * | full 128×128 mul | `imul`+`imul`+`mul`+`add`+`add` (5 insns) | `umulh`+`madd`+`madd`+`mul` (4 insns) |
 * | divide | `call __divti3` (optimized runtime) | same |
 * | add | `add`+`adc` (carry flag) | `adds`+`adc` |
 * | sub | `sub`+`sbb` (borrow flag) | `subs`+`sbc` |
 *
 * All verified via objdump (x86-64) and clang cross-compilation (ARM64)
 * in `verify-instructions.sh`.
 *
 * Note: cinterop calls have ~10-20ns overhead (GC safepoint). For add/sub,
 * this exceeds the operation cost itself (~2ns), so [Int128.plus] and [Int128.minus]
 * use pure-Kotlin carry detection instead. GCC and Clang both recognize the
 * `(uint64_t)result < (uint64_t)operand` carry pattern and compile it to
 * `add`+`adc` anyway (verified in `verify-instructions.sh`).
 */

internal actual fun unsignedMultiplyHigh(x: Long, y: Long): Long =
    int128_multiply_high_unsigned(x, y)

internal actual fun multiply128(aHi: Long, aLo: Long, bHi: Long, bLo: Long): LongArray {
    val result = int128_mul(aHi, aLo, bHi, bLo)
    return longArrayOf(result.useContents { hi }, result.useContents { lo })
}

internal actual fun unsignedDivide128(
    dividendHi: Long, dividendLo: Long,
    divisorHi: Long, divisorLo: Long,
): LongArray {
    if (divisorHi == 0L && divisorLo == 0L) throw ArithmeticException("Division by zero")
    val q = uint128_div(dividendHi, dividendLo, divisorHi, divisorLo)
    val r = uint128_rem(dividendHi, dividendLo, divisorHi, divisorLo)
    return longArrayOf(
        q.useContents { hi }, q.useContents { lo },
        r.useContents { hi }, r.useContents { lo },
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
    val q = int128_sdiv(dividendHi, dividendLo, divisorHi, divisorLo)
    val r = int128_srem(dividendHi, dividendLo, divisorHi, divisorLo)
    return longArrayOf(
        q.useContents { hi }, q.useContents { lo },
        r.useContents { hi }, r.useContents { lo },
    )
}
