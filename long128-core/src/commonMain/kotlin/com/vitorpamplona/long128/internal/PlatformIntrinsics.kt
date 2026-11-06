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
 * - **Native (x86-64, ARM64)**: Calls C functions compiled from `__int128`
 *   (`int128.def`) through `@GCUnsafeCall`, which skips the cinterop GC
 *   state switch and lets LLVM inline the C body into the Kotlin caller:
 *   multiply-high becomes a single `mul` (x86-64) / `umulh` (ARM64).
 *
 * Division returns the `Int128` / `UInt128` result directly.
 */

/**
 * Returns the upper 64 bits of the unsigned 128-bit product `x * y`,
 * where both operands are treated as unsigned 64-bit values.
 *
 * This is the critical bottleneck operation. Platform implementations:
 * - JVM JDK 18+: `Math.unsignedMultiplyHigh` → single `MUL`/`UMULH` instruction
 * - JVM JDK 17: `Math.multiplyHigh` + signed-to-unsigned correction (3 extra insns)
 * - Android API < 31: Karatsuba 4-multiply decomposition (pure Kotlin, D8 fallback)
 * - Native: `unsigned __int128` multiply via `@GCUnsafeCall` → single `mul`/`umulh`, inlined
 */
@PublishedApi internal expect fun unsignedMultiplyHigh(x: Long, y: Long): Long

/**
 * Returns the upper 64 bits of the signed 128-bit product `x * y`.
 *
 * - JVM: `Math.multiplyHigh` (via MethodHandle) → `imul`/`smulh`; Karatsuba fallback
 * - Native: `__int128` multiply via `@GCUnsafeCall` → one-operand `imul` / `smulh`, inlined
 */
internal expect fun signedMultiplyHigh(x: Long, y: Long): Long

/**
 * High word of the full 128-by-128-bit wrapping multiply. The low word is `aLo * bLo`.
 *
 * `(aHi·2^64 + aLo) · (bHi·2^64 + bLo) mod 2^128 = aLo·bLo + (aHi·bLo + aLo·bHi)·2^64`;
 * the `aHi·bHi·2^128` term overflows and is dropped. The same formula serves signed
 * and unsigned operands, since the low 128 bits of a product do not depend on sign.
 *
 * Returning only the high word (rather than a `[hi, lo]` array) keeps `Int128.times`
 * at a single allocation: the result object. Compiles to `mul` + 2 `imul` + 2 `add`
 * on x86-64 and `umulh` + 2 `madd` + `mul` on ARM64.
 */
@PublishedApi internal fun multiply128High(aHi: Long, aLo: Long, bHi: Long, bLo: Long): Long =
    unsignedMultiplyHigh(aLo, bLo) + aHi * bLo + aLo * bHi

/**
 * Unsigned 128-bit quotient and remainder (callers: [com.vitorpamplona.long128.UInt128.div]
 * and [com.vitorpamplona.long128.UInt128.rem]).
 *
 * Each returns its result directly, so on native the only cost beyond the division
 * itself is a register move: no `LongArray` and no object.
 *
 * @throws ArithmeticException if the divisor is zero.
 *
 * - JVM: pure-Kotlin binary long division ([unsignedDivRem] in SoftwareArithmetic.kt).
 * - Native: `unsigned __int128` division via `@GCUnsafeCall` → one `__udivti3` call;
 *   when a caller needs both quotient and remainder LLVM shares the one division.
 */
internal expect fun unsignedQuotient128(
    dividendHi: Long, dividendLo: Long, divisorHi: Long, divisorLo: Long,
): com.vitorpamplona.long128.UInt128

internal expect fun unsignedRemainder128(
    dividendHi: Long, dividendLo: Long, divisorHi: Long, divisorLo: Long,
): com.vitorpamplona.long128.UInt128

/**
 * Signed 128-bit quotient and remainder, truncated toward zero like Kotlin's `/` and `%`.
 * The remainder takes the sign of the dividend.
 *
 * @throws ArithmeticException if the divisor is zero.
 * @throws ArithmeticException (quotient only) if the dividend is `MIN_VALUE` and the
 *   divisor is `-1`: the result would overflow 128 bits. The remainder is then 0.
 *
 * - JVM: unsigned division of the absolute values, then sign fix-up.
 * - Native: `__int128` division via `@GCUnsafeCall` → one `__divti3` call.
 */
internal expect fun signedQuotient128(
    dividendHi: Long, dividendLo: Long, divisorHi: Long, divisorLo: Long,
): com.vitorpamplona.long128.Int128

internal expect fun signedRemainder128(
    dividendHi: Long, dividendLo: Long, divisorHi: Long, divisorLo: Long,
): com.vitorpamplona.long128.Int128
