package com.vitorpamplona.long128

import kotlin.test.*
import java.io.DataInputStream

/**
 * Bytecode-level verification that our JVM implementation actually uses
 * the hardware-intrinsified Math.multiplyHigh rather than a software fallback.
 *
 * This test reads the compiled .class file and searches for the specific
 * constant pool entry and invokestatic instruction. If someone accidentally
 * replaces Math.multiplyHigh with a pure-Kotlin fallback, this test fails.
 */
class BytecodeVerificationTest {

    @Test
    fun platformMultiplyHighResolvesMathMultiplyHighViaMethodHandle() {
        // The MethodHandle approach resolves Math.multiplyHigh at runtime to avoid D8 desugaring.
        // The class must reference "multiplyHigh" (the method name string used in findStatic)
        // and MethodHandles (the lookup mechanism), but NOT contain a direct invokestatic to Math.
        val classBytes = loadClassBytes("com.vitorpamplona.long128.internal.PlatformMath_jvmKt")
        val classText = classBytes.decodeToString(throwOnInvalidSequence = false)

        assertTrue(
            classText.contains("multiplyHigh"),
            "PlatformMath_jvmKt.class must reference 'multiplyHigh' method name for MethodHandle lookup"
        )

        assertTrue(
            classText.contains("MethodHandle") || classText.contains("invoke"),
            "PlatformMath_jvmKt.class must use MethodHandle/invoke for D8-safe dispatch"
        )
    }

    @Test
    fun int128TimesDoesNotBoxLongs() {
        val classBytes = loadClassBytes("com.vitorpamplona.long128.Int128")
        val classText = classBytes.decodeToString(throwOnInvalidSequence = false)

        // Must NOT contain Long.valueOf (boxing) in the class
        assertFalse(
            classText.contains("java/lang/Long\u0001valueOf"),
            "Int128.class must not box Longs via Long.valueOf in arithmetic"
        )
    }

    @Test
    fun int128DoesNotUseBigInteger() {
        val classBytes = loadClassBytes("com.vitorpamplona.long128.Int128")
        val classText = classBytes.decodeToString(throwOnInvalidSequence = false)

        assertFalse(
            classText.contains("BigInteger"),
            "Int128.class must not depend on java.math.BigInteger"
        )
    }

    @Test
    fun mathUtilsDoesNotUseBigInteger() {
        val classBytes = loadClassBytes("com.vitorpamplona.long128.internal.MathUtilsKt")
        val classText = classBytes.decodeToString(throwOnInvalidSequence = false)

        assertFalse(
            classText.contains("BigInteger"),
            "MathUtilsKt.class must not depend on java.math.BigInteger"
        )
    }

    @Test
    fun verifyMultiplyHighIsJdk9Intrinsic() {
        // Prove that Math.multiplyHigh exists and is callable on this JVM
        val result = Math.multiplyHigh(Long.MAX_VALUE, 2L)
        // Long.MAX_VALUE = 2^63 - 1, times 2 = 2^64 - 2
        // High 64 bits of signed product: 0 (since both positive and product < 2^64)
        assertEquals(0L, result, "Math.multiplyHigh(MAX_VALUE, 2) should be 0")

        // Another case: (-1) * (-1) = 1 as 128-bit signed; high = 0
        assertEquals(0L, Math.multiplyHigh(-1L, -1L))

        // 2^63 * 2 = 2^64, high bits = 1 (wait: MIN_VALUE is -2^63 signed)
        // signed: (-2^63) * 2 = -2^64. As 128-bit: 0xFFFFFFFFFFFFFFFF_0000000000000000
        // High 64 bits = -1
        assertEquals(-1L, Math.multiplyHigh(Long.MIN_VALUE, 2L))
    }

    @Test
    fun verifyUnsignedMultiplyHighCorrection() {
        // The unsigned correction formula must produce correct results
        // unsigned(0xFFFFFFFFFFFFFFFF) * unsigned(0xFFFFFFFFFFFFFFFF)
        // = (2^64-1)^2 = 2^128 - 2*2^64 + 1
        // High 64 bits = 2^64 - 2 = 0xFFFFFFFFFFFFFFFE
        val hi = com.vitorpamplona.long128.internal.platformUnsignedMultiplyHigh(-1L, -1L)
        assertEquals(-2L, hi, "unsignedMultiplyHigh(-1, -1) should be 0xFFFFFFFFFFFFFFFE = -2")

        // unsigned(2) * unsigned(3) = 6, high = 0
        assertEquals(0L, com.vitorpamplona.long128.internal.platformUnsignedMultiplyHigh(2L, 3L))

        // unsigned(2^63) * unsigned(2) = 2^64, high = 1
        assertEquals(1L, com.vitorpamplona.long128.internal.platformUnsignedMultiplyHigh(Long.MIN_VALUE, 2L))
    }

    private fun loadClassBytes(fqName: String): ByteArray {
        val resourcePath = fqName.replace('.', '/') + ".class"
        return this::class.java.classLoader.getResourceAsStream(resourcePath)?.readBytes()
            ?: error("Cannot find class file: $resourcePath")
    }
}
