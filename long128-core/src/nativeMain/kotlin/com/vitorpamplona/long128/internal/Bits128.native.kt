@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.vitorpamplona.long128.internal

import kotlin.native.internal.GCUnsafeCall
import kotlinx.cinterop.Vector128

/**
 * 128 bits as a register value. Lane 0 (bytes 0-7) is the low word, lane 1 the high word.
 *
 * Kotlin can only build a `Vector128` from four `Int`s, which compiles to a chain of
 * shifts and shuffles. `int128_bits` (int128.def) builds it from two 64-bit words, so
 * after inlining it is `movq` + `movq` + `punpcklqdq` on x86-64 and `fmov` + `mov` on ARM64.
 */
internal actual typealias Bits128 = Vector128

@GCUnsafeCall("int128_bits")
private external fun cBits128(hi: Long, lo: Long): Vector128

@PublishedApi internal actual fun bits128(hi: Long, lo: Long): Bits128 = cBits128(hi, lo)

@PublishedApi internal actual fun Bits128.hiWord(): Long = getLongAt(1)

@PublishedApi internal actual fun Bits128.loWord(): Long = getLongAt(0)
