@file:Suppress("NOTHING_TO_INLINE")

package com.vitorpamplona.long128

/**
 * A flat array of [Int128] values backed by a single [LongArray] of size `2 * n`.
 *
 * ## Why this exists
 *
 A `List<Int128>` or `Array<Int128>` stores each element boxed. `Int128Array` stores
 * values in one contiguous `LongArray` with layout `[hi₀, lo₀, hi₁, lo₁, ...]`: no
 * per-element object headers, and on native, `get` and `set` never allocate (an
 * `Int128` is a register value there). On the JVM `get` creates one small object,
 * which escape analysis usually removes.

 */
class Int128Array(val size: Int) {
    @PublishedApi internal val data = LongArray(size * 2)

    inline operator fun get(index: Int): Int128 {
        val offset = index * 2
        return Int128(data[offset], data[offset + 1])
    }

    inline operator fun set(index: Int, value: Int128) {
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

/**
 * Creates an [Int128Array] from the given values: `int128ArrayOf(listOf(a, b, c))`.
 *
 * Takes a collection rather than `vararg` because Kotlin does not allow `vararg`
 * parameters of a value-class type.
 */
fun int128ArrayOf(values: Collection<Int128>): Int128Array = values.toInt128Array()

/** Copies these values into a new flat [Int128Array]. */
fun Collection<Int128>.toInt128Array(): Int128Array {
    val array = Int128Array(size)
    var i = 0
    for (v in this) array[i++] = v
    return array
}

/**
 * A flat array of [UInt128] values backed by a single [LongArray] of size `2 * n`.
 *
 * Same design rationale as [Int128Array]. See its documentation for details.
 */
class UInt128Array(val size: Int) {
    @PublishedApi internal val data = LongArray(size * 2)

    inline operator fun get(index: Int): UInt128 {
        val offset = index * 2
        return UInt128(data[offset], data[offset + 1])
    }

    inline operator fun set(index: Int, value: UInt128) {
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

/**
 * Creates a [UInt128Array] from the given values: `uint128ArrayOf(listOf(a, b, c))`.
 *
 * Takes a collection rather than `vararg` because Kotlin does not allow `vararg`
 * parameters of a value-class type.
 */
fun uint128ArrayOf(values: Collection<UInt128>): UInt128Array = values.toUInt128Array()

/** Copies these values into a new flat [UInt128Array]. */
fun Collection<UInt128>.toUInt128Array(): UInt128Array {
    val array = UInt128Array(size)
    var i = 0
    for (v in this) array[i++] = v
    return array
}
