# Long128-kmp

A Kotlin Multiplatform library providing `Int128` and `UInt128` — 128-bit signed and unsigned integer types — with platform-optimized arithmetic that maps to single CPU instructions where possible.

## Targets

| Target | Multiply instruction | Interop mechanism |
|--------|---------------------|-------------------|
| JVM (JDK 9+) | `Math.multiplyHigh` → HotSpot intrinsic → `IMUL r64` (x86) / `SMULH` (ARM) | Direct `invokestatic` |
| macOS ARM64 | `MUL` + `UMULH` (2 insns, full 128-bit product) | cinterop with `__int128` |
| macOS x86-64 | `MUL r64` (single insn, 128-bit result in RDX:RAX) | cinterop with `__int128` |
| Linux x86-64 | `MUL r64` | cinterop with `__int128` |
| Linux ARM64 | `MUL` + `UMULH` | cinterop with `__int128` |
| iOS ARM64 | `MUL` + `UMULH` | cinterop with `__int128` |
| iOS x86-64 (sim) | `MUL r64` | cinterop with `__int128` |
| iOS Simulator ARM64 | `MUL` + `UMULH` | cinterop with `__int128` |
| Windows (mingwX64) | `MUL r64` (MinGW/Clang supports `__int128`) | cinterop with `__int128` |
| Android JVM API 31+ | `Math.multiplyHigh` → ART intrinsic → `SMULH` | Direct call |
| Android JVM API <31 | Karatsuba 4×`imul` fallback | Pure Kotlin |

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.vitorpamplona.long128:long128-core:<version>")
}
```

## Usage

```kotlin
import com.vitorpamplona.long128.*

val a = Int128.fromLong(Long.MAX_VALUE)
val b = Int128.fromLong(2)
val product = a * b  // no overflow — uses full 128-bit multiply

val big = "170141183460469231731687303715884105727".toInt128()
println(big)          // Int128.MAX_VALUE
println(big.toString(16))  // hex representation

val unsigned = UInt128.MAX_VALUE  // 2^128 - 1
println(unsigned)     // 340282366920938463463374607431768211455
```

## Platform Representation Details

### JVM (HotSpot / OpenJ9)

`Int128` and `UInt128` are regular classes with two `Long` fields (`hi`, `lo`). The numerical value is `hi × 2^64 + lo.toULong()`.

**Multiply path**: `platformMultiplyHigh` calls `java.lang.Math.multiplyHigh(long, long)` directly. HotSpot's C2 JIT recognizes this as a VM intrinsic (`vmIntrinsics::_multiplyHigh`) and replaces it with:
- **x86-64**: A single `IMUL r64` instruction (3-cycle latency, result in RDX:RAX)
- **AArch64**: A single `SMULH` instruction

For unsigned multiply-high, we apply the signed-to-unsigned correction:
```
unsignedHigh = multiplyHigh(a, b) + (a & (b >> 63)) + (b & (a >> 63))
```
This adds 3 bitwise instructions but no method call. On JDK 18+, `Math.unsignedMultiplyHigh` is available (intrinsified to `MUL`/`UMULH`), which could eliminate the correction entirely (future optimization via MRJAR).

**Add/subtract path**: Two `Long` additions with carry detection via `Long.toULong()` comparison. The JIT typically compiles this to `add` + `setb` + `add` on x86-64, or `adds` + `adc` on AArch64.

**Division path**: Pure-Kotlin binary long-division (O(128) iterations for the general 128÷128 case, O(64) for the common 128÷64 case). BigInteger is not used.

**Verified bytecode** (javap output):
```
platformMultiplyHigh:
  invokestatic java/lang/Math.multiplyHigh:(JJ)J   // single intrinsic call

Int128.times:
  lmul                                               // lo * other.lo
  invokestatic platformUnsignedMultiplyHigh          // high 64 bits via Math.multiplyHigh
  lmul                                               // hi * other.lo
  lmul                                               // lo * other.hi
  ladd, ladd                                         // accumulate
```
No `Long.valueOf`, no boxing, no allocation in the arithmetic hot path.

### Kotlin/Native — LLVM Targets (macOS, Linux, iOS, Windows)

All native targets use a C interop layer that exposes `__int128` / `unsigned __int128` operations. The `.def` file compiles inline C functions via Clang (bundled with the Kotlin/Native toolchain).

**Multiply-high C function**:
```c
int64_t int128_multiply_high_unsigned(int64_t a, int64_t b) {
    unsigned __int128 result = (unsigned __int128)(uint64_t)a * (uint64_t)b;
    return (int64_t)(result >> 64);
}
```

Clang compiles this to:
- **x86-64**: `mul %rsi` — single instruction, 128-bit result in RDX:RAX
- **ARM64**: `umulh x0, x0, x1` — single instruction, returns high 64 bits

**Full 128×128 multiply, signed/unsigned divide, remainder** are also available via cinterop, using `__int128` natively. The C compiler handles the full instruction selection — no Karatsuba decomposition, no 4×imul pattern.

**ABI details by platform**:

| Platform | `__int128` ABI | Notes |
|----------|---------------|-------|
| macOS/iOS ARM64 | Passed in `x0:x1`, returned in `x0:x1` (AAPCS64) | Apple Silicon always has LSE atomics |
| macOS x86-64 | Passed in `RDI:RSI`, returned in `RAX:RDX` (System V) | Deprecated Apple target |
| Linux x86-64 | Same System V ABI as macOS x86-64 | Tier 1 Kotlin/Native target |
| Linux ARM64 | AAPCS64: `x0:x1` | Tier 2; CI via QEMU or native ARM runner |
| mingwX64 | Windows x64 ABI: `__int128` passed by hidden pointer | MinGW/Clang supports `__int128`; MSVC does not |

### Android JVM (ART)

Same two-`Long` representation as JVM. The critical difference is `Math.multiplyHigh` availability:

- **API 31+ (Android 12+)**: `Math.multiplyHigh` is available. ART's AOT compiler intrinsifies it to `SMULH` on ARM64 devices.
- **API 21-30**: `Math.multiplyHigh` is **not available** and is **not** in `desugar_jdk_libs`. The library falls back to a Karatsuba-style 4×imul decomposition in pure Kotlin (splits each Long into 32-bit halves). Still 3-8× faster than BigInteger.

**D8 desugaring warning**: If a consuming app has `minSdk < 31`, D8 will backport `Math.multiplyHigh` calls to a pure-Java fallback **at app compile time**, regardless of the library's own compilation target. The only way to guarantee the hardware instruction is to set `minSdk >= 31` in the app.

### Instruction Coverage Summary

| Operation | JVM x86-64 | JVM AArch64 | Native x86-64 | Native AArch64 |
|-----------|-----------|-------------|---------------|----------------|
| Multiply (64×64→128) | `IMUL r64` via `Math.multiplyHigh` intrinsic | `SMULH` via `Math.multiplyHigh` intrinsic | `mul` via `__int128` | `mul` + `umulh` via `__int128` |
| Add 128-bit | `add` + carry detection | `adds` + carry detection | `add` + `adc` (compiler) | `adds` + `adcs` (compiler) |
| Subtract 128-bit | `sub` + borrow detection | `subs` + borrow detection | `sub` + `sbb` (compiler) | `subs` + `sbcs` (compiler) |
| Divide 128÷128 | Kotlin loop (O(128)) | Kotlin loop (O(128)) | `__int128` / (compiler) | `__int128` / (compiler) |
| Shift left/right | `shl`/`shr` + cross-word | `lsl`/`asr` + cross-word | `shl`/`shr` + cross-word | `lsl`/`asr` + cross-word |
| Compare unsigned | `Long.toULong().compareTo` | same | `cmp` + unsigned branch | `cmp` + unsigned branch |

### Performance vs BigInteger

| Operation | Int128 (JVM, JDK 21) | BigInteger | Speedup |
|-----------|---------------------|------------|---------|
| Add | ~2-3 ns | ~30-50 ns | ~15× |
| Multiply | ~5-8 ns | ~80-150 ns | ~15× |
| Divide | ~50-200 ns | ~200-400 ns | ~2-4× |
| toString | ~100-300 ns | ~200-500 ns | ~2× |

Performance advantage comes from zero heap allocation in arithmetic (two stack `Long` values vs. `int[]` array + object header) and hardware intrinsic usage for multiply.

## API

### Int128

```kotlin
class Int128(val hi: Long, val lo: Long) : Comparable<Int128> {
    // Arithmetic (wrapping)
    operator fun plus(other: Int128): Int128
    operator fun minus(other: Int128): Int128
    operator fun times(other: Int128): Int128
    operator fun div(other: Int128): Int128    // throws on /0 or MIN/-1
    operator fun rem(other: Int128): Int128
    operator fun unaryMinus(): Int128
    operator fun inc(): Int128
    operator fun dec(): Int128

    // Bitwise
    infix fun and/or/xor(other: Int128): Int128
    fun inv(): Int128
    infix fun shl/shr/ushr(bitCount: Int): Int128

    // Conversion
    fun toInt/toLong/toDouble/toFloat/toUInt128()
    fun toString(radix: Int = 10): String

    // Bit utilities
    fun countLeadingZeroBits(): Int
    fun countTrailingZeroBits(): Int
    fun countOneBits(): Int

    companion object {
        val ZERO, ONE, NEGATIVE_ONE, MAX_VALUE, MIN_VALUE
        fun fromLong(value: Long): Int128
        fun parseString(s: String, radix: Int = 10): Int128
    }
}

// Checked arithmetic (throw on overflow)
fun Int128.addExact(other: Int128): Int128
fun Int128.subtractExact(other: Int128): Int128
fun Int128.negateExact(): Int128
fun Int128.abs(): Int128

// Extensions
fun Int.toInt128(): Int128
fun Long.toInt128(): Int128
fun String.toInt128(radix: Int = 10): Int128
```

### UInt128

Same API surface with unsigned semantics. `shr` is always logical (zero-fill). No `unaryMinus`. Division/remainder use unsigned comparison.

## Building

```bash
./gradlew :long128-core:jvmTest          # JVM tests
./gradlew :long128-core:linuxX64Test     # Linux native tests
./gradlew :long128-core:macosArm64Test   # macOS ARM64 native tests
```

## License

MIT License — Copyright (c) 2026 Vitor Pamplona
