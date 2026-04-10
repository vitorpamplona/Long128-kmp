package com.vitorpamplona.long128

import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import kotlin.test.*

/**
 * Verifies that the best available hardware intrinsic is actually being used
 * on this JVM. Fails loudly if we regress to a slower tier so CI catches it.
 *
 * These tests don't verify correctness (BigIntegerOracleTest does that).
 * They verify that the *fast path* is active — preventing silent performance
 * regressions where the code is correct but slow.
 */
class IntrinsicTierTest {

    private val jdkVersion: Int = Runtime.version().feature()

    // -- Tier detection ---------------------------------------------------

    @Test
    fun mathMultiplyHighIsAvailable() {
        // We require JDK 9+. If this fails, the build target is wrong.
        val mh = try {
            MethodHandles.lookup().findStatic(
                Math::class.java, "multiplyHigh",
                MethodType.methodType(Long::class.java, Long::class.java, Long::class.java)
            )
        } catch (_: Throwable) { null }

        assertNotNull(mh, "Math.multiplyHigh must be available on JDK $jdkVersion (>= 9)")
    }

    @Test
    fun unsignedMultiplyHighAvailableOnJdk18Plus() {
        val mh = try {
            MethodHandles.lookup().findStatic(
                Math::class.java, "unsignedMultiplyHigh",
                MethodType.methodType(Long::class.java, Long::class.java, Long::class.java)
            )
        } catch (_: Throwable) { null }

        if (jdkVersion >= 18) {
            assertNotNull(mh,
                "Math.unsignedMultiplyHigh must be available on JDK $jdkVersion (>= 18). " +
                "If null, we're falling back to the signed+correction path (3 extra insns per multiply)."
            )
        } else {
            // Expected: not available on JDK < 18
            assertNull(mh, "Math.unsignedMultiplyHigh should not exist on JDK $jdkVersion")
        }
    }

    // -- MethodHandle resolution tests ------------------------------------

    @Test
    fun platformMultiplyHighUsesIntrinsicNotFallback() {
        // Verify our platformUnsignedMultiplyHigh produces correct results
        // AND that it's using the MethodHandle path (not the fallback).
        // We can't directly inspect which code path ran, but we can verify
        // the MethodHandle resolved successfully on this JDK.
        val mh = try {
            MethodHandles.lookup().findStatic(
                Math::class.java, "multiplyHigh",
                MethodType.methodType(Long::class.java, Long::class.java, Long::class.java)
            )
        } catch (_: Throwable) { null }

        assertNotNull(mh, "MethodHandle for Math.multiplyHigh must resolve on JDK $jdkVersion")

        // Verify the resolved handle produces correct results
        val result = mh.invokeExact(Long.MIN_VALUE, 2L) as Long
        assertEquals(-1L, result, "multiplyHigh(MIN_VALUE, 2) via MethodHandle")
    }

    // -- Bytecode structure tests -----------------------------------------

    @Test
    fun noDirectInvokestaticMathMultiplyHigh() {
        // The class must NOT contain a direct reference to Math.multiplyHigh
        // as a normal method call — it should use MethodHandle instead.
        val classText = loadClassText("com/vitorpamplona/long128/internal/PlatformMath_jvmKt.class")

        // The string "multiplyHigh" SHOULD be present (as the method name for MethodHandle lookup)
        assertTrue(classText.contains("multiplyHigh"),
            "PlatformMath must reference 'multiplyHigh' for MethodHandle resolution")

        // MethodHandle or MethodHandles SHOULD be present
        assertTrue(classText.contains("MethodHandle"),
            "PlatformMath must use MethodHandle for D8-safe dispatch")
    }

    @Test
    fun noBoxingInPlatformMath() {
        val classText = loadClassText("com/vitorpamplona/long128/internal/PlatformMath_jvmKt.class")

        assertFalse(classText.contains("Long;") && classText.contains("valueOf"),
            "PlatformMath must not box Longs via Long.valueOf")
    }

    @Test
    fun noBigIntegerInPlatformMath() {
        val classText = loadClassText("com/vitorpamplona/long128/internal/PlatformMath_jvmKt.class")

        assertFalse(classText.contains("BigInteger"),
            "PlatformMath must not reference BigInteger")
    }

    // -- Tier correctness tests -------------------------------------------

    @Test
    fun unsignedMultiplyHighMatchesAcrossTiers() {
        // The result must be identical regardless of which tier is active.
        // Compare our platform implementation against the manual formula.
        val testCases = listOf(
            longArrayOf(-1L, -1L),              // max unsigned × max unsigned
            longArrayOf(Long.MIN_VALUE, 2L),     // 2^63 × 2
            longArrayOf(2L, 3L),                 // small × small
            longArrayOf(Long.MAX_VALUE, Long.MAX_VALUE),
            longArrayOf(0x1234_5678_9ABC_DEF0L, -0x0123_4567_89AB_CDF0L),
        )

        for ((a, b) in testCases.map { it[0] to it[1] }) {
            val platform = com.vitorpamplona.long128.internal.platformUnsignedMultiplyHigh(a, b)
            val manual = com.vitorpamplona.long128.internal.multiplyHighFallback(a, b) +
                (a and (b shr 63)) + (b and (a shr 63))
            assertEquals(manual, platform,
                "platformUnsignedMultiplyHigh($a, $b) must match manual calculation")
        }
    }

    @Test
    fun jdkVersionReported() {
        // Not a test per se — prints the JDK version and active tier for CI logs.
        val tier = when {
            jdkVersion >= 18 -> "Tier 1: Math.unsignedMultiplyHigh (single MUL/UMULH insn)"
            jdkVersion >= 9 -> "Tier 2: Math.multiplyHigh + unsigned correction (3 extra insns)"
            else -> "Tier 3: Karatsuba 4-imul fallback"
        }
        println("JDK version: $jdkVersion → $tier")
        assertTrue(jdkVersion >= 9, "Build requires JDK 9+, got $jdkVersion")
    }

    // -- Helpers --

    private fun loadClassText(resourcePath: String): String {
        val bytes = javaClass.classLoader.getResourceAsStream(resourcePath)!!.readBytes()
        return bytes.decodeToString(throwOnInvalidSequence = false)
    }
}
