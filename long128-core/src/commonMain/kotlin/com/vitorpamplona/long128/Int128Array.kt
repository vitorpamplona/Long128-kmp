package com.vitorpamplona.long128

/**
 * A flat array of Int128 values backed by a LongArray of size 2*n.
 *
 * Avoids the per-element heap allocation that Array<Int128> would incur.
 * Layout: [hi0, lo0, hi1, lo1, hi2, lo2, ...] in the backing array.
 *
 * On JVM, this is a single long[] allocation — no object headers per element.
 * On Native, the backing array can be pinned and passed directly to cinterop
 * batch operations (int128_batch_add, int128_batch_mul).
 */
class Int128Array(val size: Int) {
    internal val data = LongArray(size * 2)

    operator fun get(index: Int): Int128 {
        val i = index * 2
        return Int128(data[i], data[i + 1])
    }

    operator fun set(index: Int, value: Int128) {
        val i = index * 2
        data[i] = value.hi
        data[i + 1] = value.lo
    }

    operator fun iterator(): Iterator<Int128> = object : Iterator<Int128> {
        private var pos = 0
        override fun hasNext() = pos < size
        override fun next(): Int128 {
            if (pos >= size) throw NoSuchElementException()
            return get(pos++)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is Int128Array && size == other.size && data.contentEquals(other.data)

    override fun hashCode(): Int = data.contentHashCode()

    override fun toString(): String = buildString {
        append('[')
        for (i in 0 until size) {
            if (i > 0) append(", ")
            append(get(i))
        }
        append(']')
    }
}

fun int128ArrayOf(vararg values: Int128): Int128Array {
    val arr = Int128Array(values.size)
    for (i in values.indices) arr[i] = values[i]
    return arr
}

/**
 * A flat array of UInt128 values backed by a LongArray of size 2*n.
 */
class UInt128Array(val size: Int) {
    internal val data = LongArray(size * 2)

    operator fun get(index: Int): UInt128 {
        val i = index * 2
        return UInt128(data[i], data[i + 1])
    }

    operator fun set(index: Int, value: UInt128) {
        val i = index * 2
        data[i] = value.hi
        data[i + 1] = value.lo
    }

    operator fun iterator(): Iterator<UInt128> = object : Iterator<UInt128> {
        private var pos = 0
        override fun hasNext() = pos < size
        override fun next(): UInt128 {
            if (pos >= size) throw NoSuchElementException()
            return get(pos++)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is UInt128Array && size == other.size && data.contentEquals(other.data)

    override fun hashCode(): Int = data.contentHashCode()

    override fun toString(): String = buildString {
        append('[')
        for (i in 0 until size) {
            if (i > 0) append(", ")
            append(get(i))
        }
        append(']')
    }
}

fun uint128ArrayOf(vararg values: UInt128): UInt128Array {
    val arr = UInt128Array(values.size)
    for (i in values.indices) arr[i] = values[i]
    return arr
}
