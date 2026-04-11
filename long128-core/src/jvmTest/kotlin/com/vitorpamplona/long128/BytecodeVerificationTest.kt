package com.vitorpamplona.long128

import kotlin.test.*

/**
 * Bytecode-level verification that the JVM implementation uses MethodHandle
 * dispatch to Math.multiplyHigh (not a direct invokestatic that D8 could desugar),
 * and that no boxing or BigInteger dependencies exist in the compiled output.
 */
class BytecodeVerificationTest {

    @Test
    fun intrinsicsClassReferencesMultiplyHighViaMethodHandle() {
        val classText = loadClassText("com.vitorpamplona.long128.internal.PlatformIntrinsics_jvmKt")

        assertTrue(
            classText.contains("multiplyHigh"),
            "PlatformIntrinsics_jvmKt must reference 'multiplyHigh' for MethodHandle lookup"
        )
        assertTrue(
            classText.contains("MethodHandle") || classText.contains("invoke"),
            "PlatformIntrinsics_jvmKt must use MethodHandle for D8-safe dispatch"
        )
    }

    @Test
    fun int128DoesNotBoxLongs() {
        val classText = loadClassText("com.vitorpamplona.long128.Int128")
        assertFalse(
            classText.contains("java/lang/Long\u0001valueOf"),
            "Int128.class must not box Longs via Long.valueOf"
        )
    }

    @Test
    fun int128DoesNotUseBigInteger() {
        val classText = loadClassText("com.vitorpamplona.long128.Int128")
        assertFalse(classText.contains("BigInteger"), "Int128.class must not reference BigInteger")
    }

    @Test
    fun softwareArithmeticDoesNotUseBigInteger() {
        val classText = loadClassText("com.vitorpamplona.long128.internal.SoftwareArithmeticKt")
        assertFalse(classText.contains("BigInteger"), "SoftwareArithmeticKt must not reference BigInteger")
    }

    @Test
    fun mathMultiplyHighIsCallableOnThisJvm() {
        assertEquals(0L, Math.multiplyHigh(Long.MAX_VALUE, 2L))
        assertEquals(0L, Math.multiplyHigh(-1L, -1L))
        assertEquals(-1L, Math.multiplyHigh(Long.MIN_VALUE, 2L))
    }

    @Test
    fun unsignedMultiplyHighCorrectionIsCorrect() {
        // (2^64-1) * (2^64-1) = 2^128 - 2^65 + 1 → high 64 bits = 0xFFFFFFFFFFFFFFFE
        assertEquals(-2L, com.vitorpamplona.long128.internal.unsignedMultiplyHigh(-1L, -1L))
        assertEquals(0L, com.vitorpamplona.long128.internal.unsignedMultiplyHigh(2L, 3L))
        assertEquals(1L, com.vitorpamplona.long128.internal.unsignedMultiplyHigh(Long.MIN_VALUE, 2L))
    }

    private fun loadClassText(fqName: String): String {
        val path = fqName.replace('.', '/') + ".class"
        val bytes = this::class.java.classLoader.getResourceAsStream(path)?.readBytes()
            ?: error("Cannot find class file: $path")
        return bytes.decodeToString(throwOnInvalidSequence = false)
    }
}
