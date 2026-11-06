package com.vitorpamplona.long128

/**
 * Returns the upper 64 bits of the signed 128-bit product of [x] and [y].
 *
 * Same contract as `java.lang.Math.multiplyHigh`. Compiles to a single instruction:
 * - JVM: `Math.multiplyHigh` intrinsic → `imul` (x86-64) / `smulh` (ARM64)
 * - Native: one-operand `imul` (x86-64) / `smulh` (ARM64), inlined into the caller
 */
fun multiplyHigh(x: Long, y: Long): Long =
    com.vitorpamplona.long128.internal.signedMultiplyHigh(x, y)

/**
 * Returns the upper 64 bits of the unsigned 128-bit product of [x] and [y], with both
 * operands read as unsigned 64-bit values.
 *
 * Same contract as `java.lang.Math.unsignedMultiplyHigh` (JDK 18+), on every target:
 * - JVM 18+: `Math.unsignedMultiplyHigh` intrinsic → `mul` / `umulh`
 * - JVM 17 and Android: `Math.multiplyHigh` + a 3-instruction unsigned correction
 *   (pure-Kotlin Karatsuba where `Math.multiplyHigh` is unavailable)
 * - Native: `mul` (x86-64) / `umulh` (ARM64), inlined into the caller
 *
 * The low 64 bits of the product are plain `x * y`.
 */
fun unsignedMultiplyHigh(x: Long, y: Long): Long =
    com.vitorpamplona.long128.internal.unsignedMultiplyHigh(x, y)

/** Upper 64 bits of the unsigned 128-bit product. The low 64 bits are `this * other`. */
fun ULong.multiplyHigh(other: ULong): ULong =
    com.vitorpamplona.long128.internal.unsignedMultiplyHigh(this.toLong(), other.toLong()).toULong()
