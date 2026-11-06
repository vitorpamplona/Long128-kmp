package com.vitorpamplona.long128

import kotlin.test.*

/** The README's quick start, compiled and checked so the docs cannot drift from the API. */
class ReadmeQuickStartTest {
    @Test fun quickStart() {
        val inputs = listOf(UInt128(0L, 3L), UInt128(1L, 5L), UInt128(-1L, -1L))

        val a = Int128.fromLong(Long.MAX_VALUE)
        val product = a * 2.toInt128()
        val max = "170141183460469231731687303715884105727".toInt128()
        val u = UInt128.MAX_VALUE
        assertEquals("ffffffffffffffffffffffffffffffff", u.toString(16))
        assertEquals("18446744073709551614", product.toString())
        assertEquals(Int128.MAX_VALUE, max)

        val acc = MutableUInt128(UInt128.ONE)
        val m = UInt128(0L, 1_000_000_007L)
        for (x in inputs) {
            acc *= x
            acc %= m
        }
        val result: UInt128 = acc.toUInt128()
        var expected = UInt128.ONE
        for (x in inputs) expected = (expected * x) % m
        assertEquals(expected, result)

        val arr = Int128Array(1_000)
        for (i in 0 until arr.size) arr[i] = Int128.fromLong(i.toLong())
        assertEquals(Int128.fromLong(999), arr[999])

        val hi = unsignedMultiplyHigh(-1L, -1L)
        assertEquals(0xFFFFFFFFFFFFFFFEuL.toLong(), hi)
    }
}
