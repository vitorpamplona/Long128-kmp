# Long128-kmp

Kotlin has no native 128-bit integer type. This library provides `Int128` and `UInt128` for Kotlin Multiplatform, with arithmetic that reaches the hardware on every target — JVM, Android, iOS, macOS, Linux, and Windows.

The goal is to answer a practical question: **how close can Kotlin get to C's `__int128` performance across platforms?** The answer varies by operation and platform, and this project measures it honestly.

## Quick start

```kotlin
import com.vitorpamplona.long128.*

val a = Int128.fromLong(Long.MAX_VALUE)
val b = Int128.fromLong(2)
val product = a * b  // full 128-bit multiply, no overflow

val big = "170141183460469231731687303715884105727".toInt128()  // Int128.MAX_VALUE
val unsigned = UInt128.MAX_VALUE  // 2^128 - 1 = 340282366920938463463374607431768211455

// For hot loops: flat array, no per-element heap allocation
val arr = Int128Array(1_000_000)
for (i in 0 until arr.size) arr[i] = Int128.fromLong(i.toLong())
```

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.vitorpamplona.long128:long128-core:<version>")
}
```

**Requirements:** JDK 17 or newer on the JVM target. Android is supported via the same JVM artifact; the D8 desugarer handles `Math.multiplyHigh` absence on API < 31 transparently (see [Platform dispatch](#platform-dispatch)).

## Performance: how close to C?

Every number below comes from `./benchmark/run-benchmarks.sh`, which compiles a C `__int128` benchmark (`gcc -O2`) and an equivalent Kotlin JVM benchmark, runs both, and prints side-by-side comparison tables.

### The raw arithmetic is within 1.2-2.7x of C

These numbers isolate instruction cost by using raw `Long` pairs — no `Int128` objects, no heap allocation. This is what Kotlin will achieve when multi-field value classes (MFVC) ship.

| Operation | C `__int128` | Kotlin (raw Longs) | Ratio | Gap |
|-----------|-------------|-------------------|-------|-----|
| multiply | 2.1 ns | 2.2 ns | **1.0x** | `Math.multiplyHigh` intrinsic matches C `mul` |
| mul-high | 0.7 ns | 1.4 ns | 2.0x | MethodHandle dispatch (needed for Android D8 safety) |
| add | <0.3 ns | 0.4 ns | ~1.3x | Carry detection compiles to same `add`+`adc` as C |
| shift | 1.7 ns | 2.2 ns | 1.3x | Cross-word shift: 4 Long ops vs C's `shld`/`shrd` |
| compare | 0.7 ns | 1.3 ns | 1.9x | Two Long comparisons vs C's `cmp`+`sbb` |

### Today's cost: heap allocation dominates

`Int128` is a regular class (Kotlin doesn't support multi-field value classes yet). Each `+`, `*`, etc. allocates a new object. This is the main overhead — not the arithmetic.

| Operation | Instruction cost | With `Int128` object | Allocation overhead |
|-----------|-----------------|---------------------|-------------------|
| add | 0.4 ns | 3-4 ns | ~8x |
| multiply | 2.2 ns | 5-6 ns | ~2.5x |
| shift | 2.2 ns | 6-7 ns | ~3x |
| compare | 1.3 ns | 3-4 ns | ~3x |
| mixed (mul-accum) | — | 7-8 ns | Realistic workload |

### Division: the largest gap

| Operation | C `__int128` | Kotlin `Int128` | Ratio |
|-----------|-------------|-----------------|-------|
| div by 7 | ~10 ns | ~60-500 ns | 6-50x |
| div by large | ~3 ns | ~25-50 ns | 8-16x |

C uses the compiler's optimized `__divti3` runtime. JVM Kotlin uses a pure-Kotlin O(128) bit-at-a-time loop (no `__int128` available on the JVM). On native targets, division goes through cinterop to `__divti3` and matches C.

### What MFVC will change

When Kotlin multi-field value classes stabilize, `Int128(hi, lo)` erases to two register-passed Longs with zero allocation:

| Operation | Today (`Int128`) | With MFVC (projected) | C `__int128` |
|-----------|-----------------|----------------------|-------------|
| add | 3-4 ns | ~0.4 ns | <0.3 ns |
| multiply | 5-6 ns | ~2.2 ns | 2.1 ns |
| shift | 6-7 ns | ~2.2 ns | 1.7 ns |
| compare | 3-4 ns | ~1.3 ns | 0.7 ns |

### Running the benchmark

```bash
./benchmark/run-benchmarks.sh    # Builds C + Kotlin, runs both, prints comparison

# Or separately:
gcc -O2 -o /tmp/bench benchmark/benchmark_c.c && /tmp/bench
./gradlew -q :benchmark:jvmRun
```

The script prints three tables: instruction cost (C vs scalar Kotlin), allocation cost (scalar vs Int128), and the full four-way comparison. The Kotlin benchmark warms up HotSpot C2 with 3 rounds before measuring. The C benchmark uses a `volatile` sink to prevent dead code elimination.

## How it works

### Representation

`Int128` and `UInt128` store two `Long` fields: `hi` (most-significant 64 bits) and `lo` (least-significant 64 bits, treated as unsigned). The value is `hi × 2^64 + lo.toULong()`.

Both types use wrapping overflow semantics, matching Kotlin's built-in `Int` and `Long`. `Int128.MAX_VALUE + Int128.ONE == Int128.MIN_VALUE`.

### Platform dispatch

Expensive operations (multiply, divide) go through `expect`/`actual` functions in `PlatformIntrinsics`. Cheap operations (add, subtract, bitwise, shifts) are pure Kotlin in `commonMain` because the compiler already generates optimal code for them.

**JVM** (requires JDK 17+) — `PlatformIntrinsics.jvm.kt` resolves `Math.multiplyHigh` and `Math.unsignedMultiplyHigh` (JDK 18+) via `MethodHandle` at class-load time. MethodHandle is used instead of `invokestatic` because Android's D8 desugarer replaces direct `Math.multiplyHigh` calls with a software fallback when the app's `minSdk < 31`. HotSpot C2 inlines constant MethodHandle fields, so desktop JVM pays no penalty. Division falls back to a pure-Kotlin O(128) loop since `__int128` is unavailable on the JVM.

**Native** — `PlatformIntrinsics.native.kt` delegates to C functions compiled from `__int128` via cinterop (`int128.def`). Clang generates optimal instructions per architecture:

| Operation | x86-64 | ARM64 |
|-----------|--------|-------|
| multiply-high | `mul rsi` (1 insn) | `umulh` (1 insn) |
| full 128×128 mul | 5 straight-line insns | 4 straight-line insns |
| add | `add`+`adc` | `adds`+`adc` |
| subtract | `sub`+`sbb` | `subs`+`sbc` |
| divide | `call __divti3` | `call __divti3` |

Add and subtract stay in pure Kotlin on native too — the carry detection pattern `(result.toULong() < operand.toULong())` compiles to the same `add`+`adc` / `adds`+`adc` as the C version (verified via objdump), and avoiding cinterop saves ~15ns of call overhead per operation.

**Android** — Same JVM implementation. On API 31+, MethodHandle resolves `Math.multiplyHigh` and ART intrinsifies it to `SMULH` on ARM64. On API <31, falls back to Karatsuba (4-multiply decomposition). `Math.unsignedMultiplyHigh` was never added to Android, so we use the signed-to-unsigned correction on all API levels.

### Project structure

```
long128-core/src/
├── commonMain/kotlin/.../
│   ├── Int128.kt                     # Signed 128-bit integer
│   ├── UInt128.kt                    # Unsigned 128-bit integer
│   ├── Int128Array.kt                # Flat LongArray-backed arrays
│   └── internal/
│       ├── PlatformIntrinsics.kt     # expect: multiply128, signedDivide128, ...
│       └── SoftwareArithmetic.kt     # Pure-Kotlin: division, toString, parsing
├── jvmMain/.../internal/
│   └── PlatformIntrinsics.jvm.kt     # actual: MethodHandle → Math.multiplyHigh
├── nativeMain/.../internal/
│   └── PlatformIntrinsics.native.kt  # actual: cinterop → __int128
└── nativeInterop/cinterop/
    └── int128.def                    # C: __int128 mul, div, add, sub, compare
```

## API

### Int128

```kotlin
class Int128(val hi: Long, val lo: Long) : Comparable<Int128> {
    operator fun plus/minus/times/div/rem(other: Int128): Int128
    operator fun unaryMinus(): Int128
    operator fun inc/dec(): Int128

    infix fun and/or/xor(other: Int128): Int128
    fun inv(): Int128
    infix fun shl/shr/ushr(bitCount: Int): Int128

    fun toInt/toLong/toDouble/toFloat/toUInt128()
    fun toString(radix: Int = 10): String
    fun countLeadingZeroBits/countTrailingZeroBits/countOneBits(): Int

    companion object {
        val ZERO, ONE, NEGATIVE_ONE, MAX_VALUE, MIN_VALUE
        fun fromLong(value: Long): Int128
        fun parseString(s: String, radix: Int = 10): Int128
    }
}

// Checked arithmetic
fun Int128.addExact/subtractExact(other: Int128): Int128
fun Int128.negateExact(): Int128
fun Int128.abs(): Int128
val Int128.sign: Int

// Extensions
fun Int.toInt128(): Int128
fun Long.toInt128(): Int128
fun String.toInt128(radix: Int = 10): Int128
```

### UInt128

Same API with unsigned semantics. `shr` is always logical (zero-fill). No `unaryMinus`.

### Int128Array / UInt128Array

Flat `LongArray`-backed containers. One allocation for N elements instead of N objects:

```kotlin
val arr = Int128Array(1_000_000)     // one long[2_000_000] — 16 MB
arr[0] = Int128.fromLong(42)
for (elem in arr) { /* ... */ }
```

## Verification

Every claim in this README is machine-verified:

```bash
./gradlew :long128-core:jvmTest   # 187 tests
./verify-instructions.sh           # 19 instruction checks
```

| What | How | Result |
|------|-----|--------|
| Arithmetic correctness | BigInteger oracle (java.math + ionspin/bignum), 500+ value pairs per op, all platforms | 187 tests pass |
| `Math.multiplyHigh` used | Bytecode inspection of compiled `.class` | Constant pool contains MethodHandle + `multiplyHigh` |
| No boxing | Bytecode inspection | No `Long.valueOf` in Int128.class |
| No BigInteger dependency | Bytecode + class file scan | Zero references |
| x86-64 `mul` instruction | `objdump -d` of compiled C interop | `mul rsi` (1 insn) |
| x86-64 `add`+`adc` | `objdump -d` | Carry flag, no branch |
| ARM64 `umulh` | `clang --target=aarch64` cross-compile + inspect `.s` | `umulh` (1 insn) |
| ARM64 `adds`+`adc` | Same cross-compile | Carry flag, no branch |
| Kotlin carry pattern → `adc` | Compile equivalent C pattern, compare objdump | Identical output |

## Building

Requires JDK 17 or newer. CI runs the JVM test suite against JDK 17 and 21.

```bash
./gradlew :long128-core:jvmTest          # JVM tests
./gradlew :long128-core:linuxX64Test     # Linux native tests
./gradlew :long128-core:macosArm64Test   # macOS ARM64 tests
./verify-instructions.sh                  # Instruction verification
./benchmark/run-benchmarks.sh             # C vs Kotlin benchmark
```

## Publishing

```bash
./gradlew :long128-core:publishToMavenLocal

# Maven Central (needs OSSRH_USERNAME, OSSRH_PASSWORD, SIGNING_KEY, SIGNING_PASSWORD)
./gradlew :long128-core:publishAllPublicationsToSonatypeRepository
```

## Known limitations

| What | Why | Workaround |
|------|-----|-----------|
| `Int128` allocates a heap object per result | Kotlin MFVC not stable | Use `Int128Array` for hot loops |
| Division is 6-50x slower than C on JVM | No `__int128` on JVM; uses O(128) Kotlin loop | On native, cinterop uses `__divti3` (matches C) |
| Android API <31: no hardware multiply | `Math.multiplyHigh` absent | Karatsuba fallback (3-8x faster than BigInteger) |
| Android: no `unsignedMultiplyHigh` | Never added to Android SDK | Signed + 3-insn correction |
| Native cinterop: ~15ns call overhead | Kotlin/Native GC safepoint | Batch APIs for arrays; add/sub stay in pure Kotlin |

## License

MIT — Copyright (c) 2026 Vitor Pamplona
