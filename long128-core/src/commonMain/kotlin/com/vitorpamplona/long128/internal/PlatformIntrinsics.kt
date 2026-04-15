package com.vitorpamplona.long128.internal

/**
 * Platform-specific intrinsics for 128-bit operations.
 *
 * Each platform provides the fastest available implementation:
 *
 * - **JVM**: Resolves `Math.multiplyHigh` and `Math.unsignedMultiplyHigh` (JDK 18+)
 *   via [java.lang.invoke.MethodHandle] at class-load time. MethodHandle dispatch
 *   avoids Android D8 desugaring (D8 replaces `invokestatic Math.multiplyHigh` with
 *   a software fallback, but cannot intercept `MethodHandle.invokeExact`). Falls
 *   back to a pure-Kotlin Karatsuba decomposition when unavailable (e.g. Android
 *   API < 31, where D8 strips `Math.multiplyHigh`).
 *
 * - **Native (x86-64, ARM64)**: Delegates to C functions compiled from `__int128`
 *   via cinterop. The C compiler (Clang) emits the optimal hardware instruction:
 *   `mul`/`umulh` on ARM64, `mul`/`imul` on x86-64, `add`+`adc` for addition.
 *
 * All functions use a `LongArray` return convention for multi-word results
 * to avoid object allocation on the return path. The packing order is always
 * `[highWord, lowWord]` for 128-bit results, or `[qHi, qLo, rHi, rLo]` for
 * division+remainder results.
 */

/**
 * Returns the upper 64 bits of the unsigned 128-bit product `x * y`,
 * where both operands are treated as unsigned 64-bit values.
 *
 * This is the critical bottleneck operation. Platform implementations:
 * - JVM JDK 18+: `Math.unsignedMultiplyHigh` → single `MUL`/`UMULH` instruction
 * - JVM JDK 17: `Math.multiplyHigh` + signed-to-unsigned correction (3 extra insns)
 * - Android API < 31: Karatsuba 4-multiply decomposition (pure Kotlin, D8 fallback)
 * - Native: cinterop `unsigned __int128` multiply → single `mul`/`umulh`
 */
internal expect fun unsignedMultiplyHigh(x: Long, y: Long): Long

/**
 * Full 128-by-128-bit wrapping multiply.
 *
 * Returns the low 128 bits of the 256-bit product, packed as `[hi, lo]`.
 * The upper 128 bits are discarded (wrapping semantics, matching Kotlin's
 * built-in `Int` and `Long` overflow behavior).
 *
 * Platform implementations:
 * - JVM: Uses [unsignedMultiplyHigh] for the cross-word product, plus three
 *   64-bit multiplies for the remaining terms.
 * - Native: Single cinterop call to `int128_mul` → 5 x86-64 instructions
 *   (`imul`, `imul`, `mul`, `add`, `add`) or 4 ARM64 instructions
 *   (`umulh`, `madd`, `madd`, `mul`), all straight-line (no loop).
 */
internal expect fun multiply128(aHi: Long, aLo: Long, bHi: Long, bLo: Long): LongArray

/**
 * Unsigned 128-bit division with remainder.
 *
 * Returns `[quotientHi, quotientLo, remainderHi, remainderLo]`.
 *
 * @throws ArithmeticException if the divisor is zero.
 *
 * Platform implementations:
 * - JVM: Pure-Kotlin binary long-division. O(64) when divisor fits in 64 bits
 *   (the common case for `toString`), O(128) for 128-by-128. See [unsignedDivRem]
 *   in SoftwareArithmetic.kt for the algorithm.
 * - Native: cinterop `unsigned __int128` division → compiler's optimized `__udivti3`
 *   runtime library.
 */
internal expect fun unsignedDivide128(
    dividendHi: Long, dividendLo: Long,
    divisorHi: Long, divisorLo: Long,
): LongArray

/**
 * Signed 128-bit division with remainder (truncated toward zero).
 *
 * Returns `[quotientHi, quotientLo, remainderHi, remainderLo]`.
 * Uses truncated division semantics, matching Kotlin's built-in `/` and `%`.
 *
 * @throws ArithmeticException if the divisor is zero.
 * @throws ArithmeticException if dividend is `MIN_VALUE` and divisor is `-1`
 *   (the result would overflow 128 bits).
 *
 * Platform implementations:
 * - JVM: Converts operands to unsigned absolute values, delegates to
 *   [unsignedDivide128], then applies sign fixup.
 * - Native: cinterop `__int128` division → compiler's optimized `__divti3` runtime.
 */
internal expect fun signedDivide128(
    dividendHi: Long, dividendLo: Long,
    divisorHi: Long, divisorLo: Long,
): LongArray
