package com.vitorpamplona.long128.internal

/**
 * The 128 bits inside an [com.vitorpamplona.long128.Int128] / [com.vitorpamplona.long128.UInt128].
 *
 * Both are value classes over this type, so what an `Int128` *is* at runtime is decided
 * per platform:
 *
 * - **Native**: `kotlinx.cinterop.Vector128`, a 16-byte value type that lives in a
 *   register (`xmm` on x86-64, `q` on ARM64). An `Int128` result is never allocated:
 *   it is passed, returned and stored like a `Long`, and boxed only where any value
 *   class is (as `Any`, a nullable, or a generic type argument).
 * - **JVM**: a small final class holding two `Long`s, so an `Int128` costs what it
 *   always did: one object, which HotSpot's escape analysis often removes.
 */
internal expect class Bits128

/** Packs [hi] (most-significant 64 bits) and [lo] (least-significant 64 bits). */
@PublishedApi internal expect fun bits128(hi: Long, lo: Long): Bits128

@PublishedApi internal expect fun Bits128.hiWord(): Long

@PublishedApi internal expect fun Bits128.loWord(): Long
