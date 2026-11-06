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

Every number below comes from `./benchmark/run-benchmarks.sh`, which compiles a C `__int128` benchmark (`gcc -O2`) and equivalent Kotlin JVM and Kotlin/Native benchmarks, runs all three, and prints side-by-side comparison tables. The JVM tables are first; [Kotlin/Native](#kotlinnative-multiply-high-matches-c) has its own section.

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

C uses the compiler's optimized `__divti3` runtime. JVM Kotlin uses a pure-Kotlin O(128) bit-at-a-time loop (no `__int128` available on the JVM). On native targets, division is one `__divti3` call like C, plus the result allocations (see below).

### What MFVC will change

When Kotlin multi-field value classes stabilize, `Int128(hi, lo)` erases to two register-passed Longs with zero allocation:

| Operation | Today (`Int128`) | With MFVC (projected) | C `__int128` |
|-----------|-----------------|----------------------|-------------|
| add | 3-4 ns | ~0.4 ns | <0.3 ns |
| multiply | 5-6 ns | ~2.2 ns | 2.1 ns |
| shift | 6-7 ns | ~2.2 ns | 1.7 ns |
| compare | 3-4 ns | ~1.3 ns | 0.7 ns |

### Kotlin/Native: multiply-high matches C

On native, the arithmetic is the same machine code as C: multiply-high is a single `mul` (x86-64) / `umulh` (ARM64) inlined into the Kotlin caller, and division is one `__divti3` call. Median of 5 runs, `linuxX64` release binary, same machine as the C column:

| Operation | C `__int128` | Before (cinterop) | Now (`@GCUnsafeCall`) | Speedup |
|-----------|-------------|-------------------|----------------------|---------|
| mul-high (raw Longs) | 0.64 ns | 1.87 ns¹ | **0.63 ns** | 3.0x |
| `Int128` multiply-add | 1.88 ns² | 80.7 ns | 23.2 ns | 3.5x |
| `Int128` div by 7 | 8.4 ns | 123.9 ns | 57.1 ns | 2.2x |
| `Int128` div by large | 2.3 ns | 114.9 ns | 40.4 ns | 2.8x |
| `Int128` mixed | 1.6 ns | 80.6 ns | 25.6 ns | 3.1x |
| `Int128Array` multiply | — | 101.7 ns | 35.9 ns | 2.8x |

¹ No public multiply-high existed before; this is the pure-Kotlin 4-multiply fallback a caller had to write. ² C's `mul` row, the same multiply-add loop.

**How.** A normal cinterop call switches the thread's GC state to "native" and back around the C function: two atomic exchanges, ~11 ns, six times the cost of the multiply. The library instead binds its C helpers with `@GCUnsafeCall`, the annotation the Kotlin/Native stdlib uses for its own small runtime functions, which calls the symbol directly. The cinterop C code is linked into the program as LLVM bitcode, so LLVM then inlines it. For that to happen on ARM64, `int128.def` compiles the C helpers for the same CPU the backend uses for Kotlin code: LLVM refuses to inline across mismatched CPU feature sets. `verify-instructions.sh` checks all of this in the linked binary.

**What is left is allocation.** Each `Int128` result is a heap object, and a Kotlin/Native allocation costs ~11 ns here, against ~3 ns on the JVM. `Int128` add is 12 ns for 0.16 ns of arithmetic; multiply-add is two allocations. A compiler fork would not change this. Multi-field value classes would, or an allocation-free API over raw `Long`s.

`GCUnsafeCall` is a Kotlin/Native internal API (opt-in `kotlin.native.internal.InternalForKotlinNative`, set in `long128-core/build.gradle.kts`). It is safe only for short, non-blocking C functions that never call back into Kotlin, which is all these helpers do. A klib carries the annotation and the consumer's compiler lowers it, so a future Kotlin release that changes it affects apps using the library at link time. [KT-85635](https://youtrack.jetbrains.com/issue/KT-85635) asks for a supported intrinsic.

### Running the benchmark

```bash
./benchmark/run-benchmarks.sh    # Builds C + JVM + Native (host target), runs all, prints comparison

# Or separately:
gcc -O2 -o /tmp/bench benchmark/benchmark_c.c && /tmp/bench
./gradlew -q :benchmark:jvmRun
./gradlew -q :benchmark:runReleaseExecutableLinuxX64   # or MacosArm64 / MacosX64 / LinuxArm64
```

The script prints four tables: instruction cost (C vs scalar Kotlin), allocation cost (scalar vs Int128), the full four-way JVM comparison, and the same four-way comparison for Kotlin/Native. `SKIP_NATIVE=1` skips the native build. The Kotlin benchmark warms up HotSpot C2 with 3 rounds before measuring. The C benchmark uses a `volatile` sink to prevent dead code elimination.

## How it works

### Representation

`Int128` and `UInt128` store two `Long` fields: `hi` (most-significant 64 bits) and `lo` (least-significant 64 bits, treated as unsigned). The value is `hi × 2^64 + lo.toULong()`.

Both types use wrapping overflow semantics, matching Kotlin's built-in `Int` and `Long`. `Int128.MAX_VALUE + Int128.ONE == Int128.MIN_VALUE`.

### Platform dispatch

Expensive operations (multiply, divide) go through `expect`/`actual` functions in `PlatformIntrinsics`. Cheap operations (add, subtract, bitwise, shifts) are pure Kotlin in `commonMain` because the compiler already generates optimal code for them.

**JVM** (requires JDK 17+) — `PlatformIntrinsics.jvm.kt` resolves `Math.multiplyHigh` and `Math.unsignedMultiplyHigh` (JDK 18+) via `MethodHandle` at class-load time. MethodHandle is used instead of `invokestatic` because Android's D8 desugarer replaces direct `Math.multiplyHigh` calls with a software fallback when the app's `minSdk < 31`. HotSpot C2 inlines constant MethodHandle fields, so desktop JVM pays no penalty. Division falls back to a pure-Kotlin O(128) loop since `__int128` is unavailable on the JVM.

**Native** — `PlatformIntrinsics.native.kt` binds C functions compiled from `__int128` (`int128.def`) with `@GCUnsafeCall`, so there is no cinterop GC state switch and LLVM inlines the C body into the Kotlin caller (see [Kotlin/Native](#kotlinnative-multiply-high-matches-c)). In the linked binary:

| Operation | x86-64 | ARM64 |
|-----------|--------|-------|
| multiply-high | `mul` (1 insn, inline) | `umulh` (1 insn, inline) |
| full 128×128 mul | `mul` + 2 `imul` + 2 `add` | `umulh` + 2 `madd` + `mul` |
| add | `add`+`adc` | `adds`+`adc` |
| subtract | `sub`+`sbb` | `subs`+`sbc` |
| divide + remainder | one `call __divti3` | one `call __divti3` |

The full multiply is Kotlin code (`multiply128High`) on top of the multiply-high, shared by every platform. Division asks C for each 64-bit word of the quotient and remainder separately so every result comes back in a register; after inlining, LLVM merges the four into one `__divti3` call. Add and subtract are pure Kotlin: the carry detection pattern `(result.toULong() < operand.toULong())` compiles to the same `add`+`adc` / `adds`+`adc` as C.

**Android** — Same JVM implementation. On API 31+, MethodHandle resolves `Math.multiplyHigh` and ART intrinsifies it to `SMULH` on ARM64. On API <31, falls back to Karatsuba (4-multiply decomposition). `Math.unsignedMultiplyHigh` was never added to Android, so we use the signed-to-unsigned correction on all API levels.

### Project structure

```
long128-core/src/
├── commonMain/kotlin/.../
│   ├── Int128.kt                     # Signed 128-bit integer
│   ├── UInt128.kt                    # Unsigned 128-bit integer
│   ├── Int128Array.kt                # Flat LongArray-backed arrays
│   ├── MultiplyHigh.kt               # Public multiplyHigh / unsignedMultiplyHigh
│   └── internal/
│       ├── PlatformIntrinsics.kt     # expect: unsignedMultiplyHigh, signedDivide128, ...
│       └── SoftwareArithmetic.kt     # Pure-Kotlin: division, toString, parsing
├── jvmMain/.../internal/
│   └── PlatformIntrinsics.jvm.kt     # actual: MethodHandle → Math.multiplyHigh
├── nativeMain/.../internal/
│   └── PlatformIntrinsics.native.kt  # actual: @GCUnsafeCall → __int128, inlined
└── nativeInterop/cinterop/
    └── int128.def                    # C: __int128 multiply-high, div/rem, reference helpers
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

### Multiply-high

The upper 64 bits of a 64×64-bit product, one instruction on every target: the building block for bignum, elliptic-curve field and hash arithmetic. Same contracts as `java.lang.Math`.

```kotlin
fun multiplyHigh(x: Long, y: Long): Long            // signed:   imul / smulh
fun unsignedMultiplyHigh(x: Long, y: Long): Long    // unsigned: mul  / umulh
fun ULong.multiplyHigh(other: ULong): ULong
```

On Kotlin/Native these inline into the caller's code across module boundaries, so a hot loop in your module gets a bare `mul` / `umulh`.

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
./gradlew :long128-core:jvmTest   # 281 tests (248 on linuxX64Test)
./verify-instructions.sh           # 27 instruction checks with both Linux benchmark binaries built
```

| What | How | Result |
|------|-----|--------|
| Arithmetic correctness | BigInteger oracle (java.math + ionspin/bignum), 500+ value pairs per op, all platforms | 281 JVM / 248 native tests pass |
| `Math.multiplyHigh` used | Bytecode inspection of compiled `.class` | Constant pool contains MethodHandle + `multiplyHigh` |
| No boxing | Bytecode inspection | No `Long.valueOf` in Int128.class |
| No BigInteger dependency | Bytecode + class file scan | Zero references |
| x86-64 `mul` instruction | `objdump -d` of compiled C interop | `mul rsi` (1 insn) |
| x86-64 `add`+`adc` | `objdump -d` | Carry flag, no branch |
| ARM64 `umulh` | `clang --target=aarch64` cross-compile + inspect `.s` | `umulh` (1 insn) |
| ARM64 `adds`+`adc` | Same cross-compile | Carry flag, no branch |
| Kotlin carry pattern → `adc` | Compile equivalent C pattern, compare objdump | Identical output |
| Native multiply-high inlined | Disassemble the linked benchmark binary (x86-64 and ARM64) | Bare `mul` / `umulh` in Kotlin code, incl. a loop in another module |
| No GC state switch | Same binary: atomic exchange within 16 insns of a multiply-high, cinterop bridge calls | None (the pre-`@GCUnsafeCall` binary fails this check) |
| One division call | Same binary: `__modti3` / `__umodti3` calls | None: remainder derived from the one `__divti3` |

## Building

Requires JDK 17 or newer. CI runs the JVM test suite against JDK 17 and 21.

```bash
./gradlew :long128-core:jvmTest          # JVM tests
./gradlew :long128-core:linuxX64Test     # Linux native tests
./gradlew :long128-core:macosArm64Test   # macOS ARM64 tests
./verify-instructions.sh                  # Instruction verification
./benchmark/run-benchmarks.sh             # C vs Kotlin JVM vs Kotlin/Native benchmark
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
| Native: ~11 ns per `Int128` allocation | Kotlin/Native allocator + GC; arithmetic itself matches C | Keep hot loops on raw `Long`s with `unsignedMultiplyHigh` |
| Native relies on `GCUnsafeCall` | Kotlin/Native internal API; no public intrinsic yet ([KT-85635](https://youtrack.jetbrains.com/issue/KT-85635)) | CI builds and checks the linked binary |

## License

MIT — Copyright (c) 2026 Vitor Pamplona
