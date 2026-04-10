package com.vitorpamplona.long128

import kotlin.test.*

class Int128ArrayTest {

    @Test fun createAndAccess() {
        val arr = Int128Array(3)
        arr[0] = Int128.fromLong(42)
        arr[1] = Int128.MAX_VALUE
        arr[2] = Int128.MIN_VALUE

        assertEquals(Int128.fromLong(42), arr[0])
        assertEquals(Int128.MAX_VALUE, arr[1])
        assertEquals(Int128.MIN_VALUE, arr[2])
    }

    @Test fun sizeCorrect() {
        val arr = Int128Array(5)
        assertEquals(5, arr.size)
        assertEquals(10, arr.data.size) // 2 longs per element
    }

    @Test fun int128ArrayOfFactory() {
        val arr = int128ArrayOf(Int128.ZERO, Int128.ONE, Int128.NEGATIVE_ONE)
        assertEquals(3, arr.size)
        assertEquals(Int128.ZERO, arr[0])
        assertEquals(Int128.ONE, arr[1])
        assertEquals(Int128.NEGATIVE_ONE, arr[2])
    }

    @Test fun iteratorWorks() {
        val arr = int128ArrayOf(Int128.fromLong(1), Int128.fromLong(2), Int128.fromLong(3))
        val list = mutableListOf<Int128>()
        for (v in arr) list.add(v)
        assertEquals(3, list.size)
        assertEquals(Int128.fromLong(1), list[0])
        assertEquals(Int128.fromLong(3), list[2])
    }

    @Test fun equalityWorks() {
        val a = int128ArrayOf(Int128.ONE, Int128.fromLong(42))
        val b = int128ArrayOf(Int128.ONE, Int128.fromLong(42))
        val c = int128ArrayOf(Int128.ONE, Int128.fromLong(43))
        assertEquals(a, b)
        assertNotEquals(a, c)
    }

    @Test fun zeroInitialized() {
        val arr = Int128Array(10)
        for (i in 0 until 10) {
            assertEquals(Int128.ZERO, arr[i])
        }
    }

    // UInt128Array

    @Test fun uint128ArrayCreateAndAccess() {
        val arr = UInt128Array(2)
        arr[0] = UInt128.MAX_VALUE
        arr[1] = UInt128.ONE
        assertEquals(UInt128.MAX_VALUE, arr[0])
        assertEquals(UInt128.ONE, arr[1])
    }

    @Test fun uint128ArrayOfFactory() {
        val arr = uint128ArrayOf(UInt128.ZERO, UInt128.MAX_VALUE)
        assertEquals(2, arr.size)
        assertEquals(UInt128.ZERO, arr[0])
        assertEquals(UInt128.MAX_VALUE, arr[1])
    }

    // Verify array operations maintain correctness through round-trip
    @Test fun arithmeticOnArrayElements() {
        val a = int128ArrayOf(Int128.fromLong(100), Int128.MAX_VALUE, Int128.MIN_VALUE)
        val b = int128ArrayOf(Int128.fromLong(200), Int128.ONE, Int128.NEGATIVE_ONE)
        val result = Int128Array(3)

        for (i in 0 until 3) {
            result[i] = a[i] + b[i]
        }

        assertEquals(Int128.fromLong(300), result[0])
        assertEquals(Int128.MIN_VALUE, result[1]) // MAX + 1 wraps
        assertEquals(Int128.MAX_VALUE, result[2]) // MIN + (-1) wraps
    }
}
