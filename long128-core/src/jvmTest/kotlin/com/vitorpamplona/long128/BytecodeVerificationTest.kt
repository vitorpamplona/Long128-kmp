package com.vitorpamplona.long128

import kotlin.test.*

/**
 * Bytecode-level verification of every compiled class file.
 *
 * Ensures the JVM compilation output matches our performance claims:
 * - MethodHandle dispatch (not invokestatic) for Math.multiplyHigh
 * - No Long.valueOf boxing anywhere in the library
 * - No BigInteger dependency anywhere
 * - Correct intrinsic resolution on this JDK
 *
 * If any of these fail, a performance regression or dependency leak occurred.
 */
class BytecodeVerificationTest {

    // ── All classes that ship in the library jar ──────────────────────

    private val allClasses = listOf(
        "com.vitorpamplona.long128.Int128",
        "com.vitorpamplona.long128.Int128\$Companion",
        "com.vitorpamplona.long128.Int128Kt",
        "com.vitorpamplona.long128.UInt128",
        "com.vitorpamplona.long128.UInt128\$Companion",
        "com.vitorpamplona.long128.UInt128Kt",
        "com.vitorpamplona.long128.Int128Array",
        "com.vitorpamplona.long128.Int128ArrayKt",
        "com.vitorpamplona.long128.UInt128Array",
        "com.vitorpamplona.long128.UInt128ArrayKt",
        "com.vitorpamplona.long128.internal.PlatformIntrinsics_jvmKt",
        "com.vitorpamplona.long128.internal.SoftwareArithmeticKt",
    )

    // ── No boxing in any class ───────────────────────────────────────

    @Test
    fun noLongBoxingInAnyClass() {
        for (fqn in allClasses) {
            val text = loadClassTextOrNull(fqn) ?: continue
            assertFalse(
                text.contains("Long;") && text.contains("valueOf"),
                "$fqn must not box Longs via Long.valueOf"
            )
        }
    }

    // ── No BigInteger dependency in any class ────────────────────────

    @Test
    fun noBigIntegerInAnyClass() {
        for (fqn in allClasses) {
            val text = loadClassTextOrNull(fqn) ?: continue
            assertFalse(
                text.contains("BigInteger"),
                "$fqn must not reference java.math.BigInteger"
            )
        }
    }

    // ── MethodHandle dispatch for multiplyHigh ───────────────────────

    @Test
    fun intrinsicsUsesMethodHandleNotInvokestatic() {
        val text = loadClassText("com.vitorpamplona.long128.internal.PlatformIntrinsics_jvmKt")

        assertTrue(
            text.contains("multiplyHigh"),
            "PlatformIntrinsics must reference 'multiplyHigh' for MethodHandle lookup"
        )
        assertTrue(
            text.contains("MethodHandle"),
            "PlatformIntrinsics must use MethodHandle (not invokestatic) for D8-safe dispatch"
        )
    }

    // ── Intrinsic correctness on this JDK ────────────────────────────

    @Test
    fun mathMultiplyHighIsCallable() {
        assertEquals(0L, Math.multiplyHigh(Long.MAX_VALUE, 2L))
        assertEquals(0L, Math.multiplyHigh(-1L, -1L))
        assertEquals(-1L, Math.multiplyHigh(Long.MIN_VALUE, 2L))
    }

    @Test
    fun unsignedMultiplyHighCorrectionIsCorrect() {
        assertEquals(-2L, com.vitorpamplona.long128.internal.unsignedMultiplyHigh(-1L, -1L))
        assertEquals(0L, com.vitorpamplona.long128.internal.unsignedMultiplyHigh(2L, 3L))
        assertEquals(1L, com.vitorpamplona.long128.internal.unsignedMultiplyHigh(Long.MIN_VALUE, 2L))
    }

    // ── Int128 and UInt128 both route through PlatformIntrinsics ─────

    @Test
    fun int128UsesPlatformIntrinsics() {
        val text = loadClassText("com.vitorpamplona.long128.Int128")
        assertTrue(text.contains("PlatformIntrinsics"), "Int128 must delegate to PlatformIntrinsics")
    }

    @Test
    fun uint128UsesPlatformIntrinsics() {
        val text = loadClassText("com.vitorpamplona.long128.UInt128")
        assertTrue(text.contains("PlatformIntrinsics"), "UInt128 must delegate to PlatformIntrinsics")
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun loadClassText(fqn: String): String {
        val path = fqn.replace('.', '/') + ".class"
        val bytes = this::class.java.classLoader.getResourceAsStream(path)?.readBytes()
            ?: error("Cannot find class file: $path")
        return bytes.decodeToString(throwOnInvalidSequence = false)
    }

    private fun loadClassTextOrNull(fqn: String): String? {
        val path = fqn.replace('.', '/') + ".class"
        return this::class.java.classLoader.getResourceAsStream(path)?.readBytes()
            ?.decodeToString(throwOnInvalidSequence = false)
    }
}
