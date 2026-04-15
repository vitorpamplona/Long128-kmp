package com.vitorpamplona.long128

/**
 * A flat array of [Int128] values backed by a single [LongArray] of size `2 * n`.
 *
 * ## Why this exists
 *
 * [Int128] is a regular class (not a value class) because Kotlin does not yet
 * support multi-field value classes. This means every `Int128` returned from an
 * operator is a heap allocation. In hot loops, this creates GC pressure.
 *
 * `Int128Array` avoids this by storing values in a contiguous `LongArray` with
 * layout `[hi₀, lo₀, hi₁, lo₁, ...]`. Getting and setting elements still creates
 * temporary `Int128` objects, but the backing storage is a single `long[]` with
 * no per-element object headers.
 *
 * ## Native batch operations
 *
 * On Kotlin/Native targets, the internal [data] array can be pinned and passed
 * directly to C batch functions (`int128_batch_add`, `int128_batch_mul`) that
 * process the entire array in a single cinterop call, amortizing the ~10-20ns
 * per-call GC safepoint overhead.
 */
class Int128Array(val size: Int) {
    @PublishedApi internal val data = LongArray(size * 2)

    operator fun get(index: Int): Int128 {
        val offset = index * 2
        return Int128(data[offset], data[offset + 1])
    }

    operator fun set(index: Int, value: Int128) {
        val offset = index * 2
        data[offset] = value.hi
        data[offset + 1] = value.lo
    }

    operator fun iterator(): Iterator<Int128> = object : Iterator<Int128> {
        private var cursor = 0
        override fun hasNext() = cursor < size
        override fun next(): Int128 {
            if (cursor >= size) throw NoSuchElementException()
            return get(cursor++)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is Int128Array && size == other.size && data.contentEquals(other.data)

    override fun hashCode(): Int = data.contentHashCode()

    // Note: we use a plain StringBuilder rather than `buildString { ... }` on
    // purpose. Inside a `buildString` lambda, the implicit receiver becomes
    // the StringBuilder, and kotlin.text provides an extension
    // `StringBuilder.get(index: Int): Char`. That means an unqualified
    // `get(i)` call inside the lambda resolves to `charAt(i)` on the builder
    // — not `Int128Array.get(i)` — and the output is silently garbage.
    override fun toString(): String {
        val sb = StringBuilder()
        sb.append('[')
        for (i in 0 until size) {
            if (i > 0) sb.append(", ")
            sb.append(get(i).toString())
        }
        sb.append(']')
        return sb.toString()
    }
}

/** Creates an [Int128Array] from the given values. */
fun int128ArrayOf(vararg values: Int128): Int128Array {
    val array = Int128Array(values.size)
    for (i in values.indices) array[i] = values[i]
    return array
}

/**
 * A flat array of [UInt128] values backed by a single [LongArray] of size `2 * n`.
 *
 * Same design rationale as [Int128Array]. See its documentation for details.
 */
class UInt128Array(val size: Int) {
    @PublishedApi internal val data = LongArray(size * 2)

    operator fun get(index: Int): UInt128 {
        val offset = index * 2
        return UInt128(data[offset], data[offset + 1])
    }

    operator fun set(index: Int, value: UInt128) {
        val offset = index * 2
        data[offset] = value.hi
        data[offset + 1] = value.lo
    }

    operator fun iterator(): Iterator<UInt128> = object : Iterator<UInt128> {
        private var cursor = 0
        override fun hasNext() = cursor < size
        override fun next(): UInt128 {
            if (cursor >= size) throw NoSuchElementException()
            return get(cursor++)
        }
    }

    override fun equals(other: Any?): Boolean =
        other is UInt128Array && size == other.size && data.contentEquals(other.data)

    override fun hashCode(): Int = data.contentHashCode()

    // See the note on [Int128Array.toString]: `buildString` shadows `get` to
    // `StringBuilder.charAt`, so we use a plain StringBuilder here instead.
    override fun toString(): String {
        val sb = StringBuilder()
        sb.append('[')
        for (i in 0 until size) {
            if (i > 0) sb.append(", ")
            sb.append(get(i).toString())
        }
        sb.append(']')
        return sb.toString()
    }
}

/** Creates a [UInt128Array] from the given values. */
fun uint128ArrayOf(vararg values: UInt128): UInt128Array {
    val array = UInt128Array(values.size)
    for (i in values.indices) array[i] = values[i]
    return array
}
