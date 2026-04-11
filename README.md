# Long128-kmp

A Kotlin Multiplatform library providing `Int128` and `UInt128` — 128-bit signed and unsigned integer types — with platform-optimized arithmetic that maps to single CPU instructions where possible.

## Targets

| Target | Multiply | Add | Divide | Interop |
|--------|----------|-----|--------|---------|
| JVM (JDK 18+) | `Math.unsignedMultiplyHigh` → `MUL`/`UMULH` | Kotlin + JIT | Kotlin O(128) loop | MethodHandle |
| JVM (JDK 9-17) | `Math.multiplyHigh` + 3-insn correction → `IMUL`/`SMULH` | Kotlin + JIT | Kotlin O(128) loop | MethodHandle |
| Android API 31+ | `Math.multiplyHigh` → ART → `SMULH` | Kotlin | Kotlin O(128) loop | MethodHandle (D8-safe) |
| Android API <31 | Karatsuba 4×imul | Kotlin | Kotlin O(128) loop | Pure Kotlin |
| Native x86-64 | `mul` (1 insn) | `add`+`adc` (2 insns) | `__divti3` (optimized) | cinterop `__int128` |
| Native ARM64 | `mul`+`umulh` (2 insns) | `adds`+`adc` (2 insns) | `__divti3` (optimized) | cinterop `__int128` |
| Native Windows | `mul` (MinGW/Clang) | `add`+`adc` | `__divti3` | cinterop `__int128` |

Native targets: macOS ARM64/x64, Linux x64/ARM64, iOS ARM64/x64/SimulatorARM64, mingwX64.

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

// Zero-allocation array for hot loops
val arr = Int128Array(1000)
for (i in 0 until arr.size) arr[i] = Int128.fromLong(i.toLong())
```

## Project Structure

```
long128-core/src/
├── commonMain/kotlin/com/vitorpamplona/long128/
│   ├── Int128.kt                  # Signed 128-bit integer
│   ├── UInt128.kt                 # Unsigned 128-bit integer
│   ├── Int128Array.kt             # Flat arrays (zero per-element allocation)
│   └── internal/
│       ├── PlatformIntrinsics.kt  # expect funs: unsignedMultiplyHigh, multiply128,
│       │                          #   signedDivide128, unsignedDivide128
│       └── SoftwareArithmetic.kt  # Pure-Kotlin fallbacks: division, comparison,
│                                  #   toString, parsing (used on JVM, reference for native)
├── jvmMain/kotlin/.../internal/
│   └── PlatformIntrinsics.jvm.kt  # actual: MethodHandle → Math.multiplyHigh (D8-safe),
│                                  #   tiered JDK 8/9/18 intrinsic selection
├── nativeMain/kotlin/.../internal/
│   └── PlatformIntrinsics.native.kt  # actual: cinterop → __int128 (mul, div, umulh)
└── nativeInterop/cinterop/
    └── int128.def                 # C functions using __int128, compiled by Clang to
                                   #   mul/umulh/add+adc/sub+sbb/__divti3
```

**Why this structure**: The `expect`/`actual` split in `PlatformIntrinsics` lets each platform
provide its best implementation for the expensive operations (multiply, divide) while sharing
the cheap ones (add, subtract, bitwise, shifts) in `commonMain`. The pure-Kotlin algorithms in
`SoftwareArithmetic` serve as both the JVM implementation (where `__int128` is unavailable) and
a correctness reference for testing the native cinterop path.

## Platform Representation Details

### JVM (HotSpot / OpenJ9)

`Int128` and `UInt128` are regular classes with two `Long` fields (`hi`, `lo`). The numerical value is `hi × 2^64 + lo.toULong()`.

**Tiered multiply intrinsics** (resolved once at class-load time via `MethodHandle` in `PlatformIntrinsics.jvm.kt`):

| JDK | Method resolved | CPU instruction | Extra overhead |
|-----|----------------|-----------------|---------------|
| 18+ | `Math.unsignedMultiplyHigh` | Single `MUL` (x86) / `UMULH` (ARM) | None |
| 9-17 | `Math.multiplyHigh` | Single `IMUL` (x86) / `SMULH` (ARM) | +3 bitwise insns for unsigned correction |
| 8 | None (fallback) | 4× `imul` (Karatsuba decomposition) | ~4× slower than hardware path |

MethodHandle dispatch is used instead of direct `invokestatic` so that Android's D8 desugarer cannot intercept the call. HotSpot C2 inlines constant MethodHandle fields, so desktop JVM performance is not affected.

**Add/subtract**: Pure Kotlin with carry detection via `Long.toULong()` comparison. HotSpot JIT typically compiles this to `add` + `setb` + `add` on x86-64, or `adds` + `adc` on AArch64.

**Division**: Pure-Kotlin binary long-division in `SoftwareArithmetic.kt`. O(64) for 128÷64 (the common case in toString), O(128) for 128÷128. No BigInteger dependency anywhere.

**Allocation**: Each `Int128` is a heap object (two Long fields). For hot loops, use `Int128Array` which stores values in a flat `LongArray` with zero per-element allocation. Kotlin multi-field value classes (MFVC) will eliminate the per-object overhead when stabilized.

### Kotlin/Native — LLVM Targets (macOS, Linux, iOS, Windows)

Multiply and divide delegate to C functions (via `PlatformIntrinsics.native.kt`) that use `__int128` / `unsigned __int128`. Clang (bundled with the Kotlin/Native toolchain) compiles these to the optimal hardware instruction for each architecture.

Add and subtract stay in pure Kotlin because cinterop call overhead (~10-20ns for GC safepoint) exceeds the operation cost (~2ns). The Kotlin carry detection pattern `(result.toULong() < operand.toULong())` is recognized by both GCC and Clang as a carry-flag test and compiles to `add`+`adc` / `adds`+`adc` — the same instructions as the `__int128` C version (verified in `verify-instructions.sh`).

**Operations routed through cinterop** (expensive, benefit from hardware):

| Operation | C function | x86-64 | ARM64 |
|-----------|-----------|--------|-------|
| Multiply (64×64→128 high) | `int128_multiply_high_unsigned` | `mul rsi` (1 insn) | `umulh` (1 insn) |
| Full 128×128 multiply | `int128_mul` | `imul`+`imul`+`mul`+`add`+`add` (5 insns) | `umulh`+`madd`+`madd`+`mul` (4 insns) |
| Signed divide | `int128_sdiv` | `call __divti3` | `call __divti3` |
| Unsigned divide | `uint128_div` | `call __udivti3` | `call __udivti3` |

**Operations staying in pure Kotlin** (cheap, cinterop overhead would be a net loss):

| Operation | Kotlin pattern | x86-64 output | ARM64 output |
|-----------|---------------|--------------|--------------|
| Add | `lo + other.lo` + carry detect | `add`+`adc` | `adds`+`adc` |
| Subtract | `lo - other.lo` + borrow detect | `sub`+`sbb` | `subs`+`sbc` |
| Compare | `Long.compareTo` / `ULong.compareTo` | `cmp` | `cmp` |
| Bitwise & shifts | Direct Long ops | 1-2 insns | 1-2 insns |

**Batch operations** (amortize cinterop call overhead for `Int128Array`): `int128_batch_add` and `int128_batch_mul` in `int128.def` process N elements in a single cinterop call with `add`+`adc` / `mul` in a tight loop.

**ABI details**:

| Platform | `__int128` ABI | Notes |
|----------|---------------|-------|
| macOS/iOS ARM64 | Passed in `x0:x1`, returned in `x0:x1` (AAPCS64) | Apple Silicon always has LSE atomics |
| macOS x86-64 | Passed in `RDI:RSI`, returned in `RAX:RDX` (System V) | Deprecated Apple target |
| Linux x86-64 | Same System V ABI as macOS x86-64 | Tier 1 Kotlin/Native target |
| Linux ARM64 | AAPCS64: `x0:x1` | Tier 2; CI via native ARM runner |
| mingwX64 | Windows x64 ABI: `__int128` passed by hidden pointer | MinGW/Clang supports `__int128`; MSVC does not |

### Android JVM (ART)

Same two-`Long` representation as JVM. The critical challenge is accessing ARM64 `SMULH`/`UMULH` instructions through the ART runtime.

**The D8 desugaring problem**: If a library calls `Math.multiplyHigh` via a normal `invokestatic`, D8 **replaces it at app compile time** with a pure-Java backport when the app's `minSdk < 31` — regardless of what the library targets. The hardware instruction never executes.

**Our solution**: We resolve `Math.multiplyHigh` via `MethodHandle.invokeExact` at class-load time. The bytecode contains `invokeExact` on a `MethodHandle`, not `invokestatic Math.multiplyHigh`, so D8 cannot intercept it.

| API level | Multiply | Add/Sub | Divide |
|-----------|---------|---------|--------|
| 31+ | `SMULH` (hardware) via MethodHandle → `Math.multiplyHigh` → ART intrinsic | Kotlin carry detection | Kotlin O(128) loop |
| 26-30 | Karatsuba 4×imul (MethodHandle resolves but `multiplyHigh` absent) | Kotlin carry detection | Kotlin O(128) loop |
| 21-25 | Karatsuba 4×imul | Kotlin carry detection | Kotlin O(128) loop |

`Math.unsignedMultiplyHigh` (JDK 18) was never added to Android's `java.lang.Math`. We use the signed-to-unsigned correction on all Android API levels.

### Full Instruction Coverage Matrix

| Operation | JVM x86-64 (JDK 18+) | JVM AArch64 (JDK 18+) | Native x86-64 | Native AArch64 | Android API 31+ |
|-----------|----------------------|----------------------|---------------|----------------|-----------------|
| Multiply 64×64→128 | `MUL` via unsignedMultiplyHigh | `UMULH` via unsignedMultiplyHigh | `mul` | `umulh` | `SMULH` + correction |
| Full 128×128 mul | multiplyHigh + 3×`lmul` | multiplyHigh + 3×`lmul` | 5 insns (no loop) | 4 insns (no loop) | multiplyHigh + 3×`lmul` |
| Add 128-bit | `add` + JIT carry | `adds` + JIT carry | `add`+`adc` | `adds`+`adc` | Kotlin carry |
| Subtract 128-bit | `sub` + JIT borrow | `subs` + JIT borrow | `sub`+`sbb` | `subs`+`sbc` | Kotlin borrow |
| Divide 128÷128 | Kotlin O(128) loop | Kotlin O(128) loop | `__divti3` | `__divti3` | Kotlin O(128) loop |
| Compare (signed) | Long.compareTo | Long.compareTo | `cmp`+`sbb`+`setl` | branchless | Long.compareTo |
| Compare (unsigned) | ULong.compareTo | ULong.compareTo | `cmp`+`sbb`+`setb` | branchless | ULong.compareTo |

### Known Limitations

| Gap | Platform | Impact | Blocked by |
|-----|----------|--------|-----------|
| Int128 is heap-allocated (not value class) | JVM, Android | One object per scalar result | Kotlin MFVC not stable; use `Int128Array` for hot loops |
| No hardware multiply on old Android | API <31 | Karatsuba 4×imul (still 3-8× faster than BigInteger) | `Math.multiplyHigh` absent from SDK |
| No `unsignedMultiplyHigh` on Android | All API levels | +3 bitwise insns per unsigned multiply | Never added to Android |
| Division is software on JVM | JVM, Android | O(128) Kotlin loop vs hardware `__divti3` on native | No `__int128` on JVM |
| Cinterop per-call overhead on native | All native | ~10-20ns GC safepoint per call | Use batch APIs for arrays |

## Performance

### Running the benchmark

```bash
./benchmark/run-benchmarks.sh
```

This builds and runs both benchmarks from scratch, then prints three side-by-side comparison tables. Requires `gcc` and the Gradle wrapper. The Kotlin benchmark warms up HotSpot C2 with 3 rounds before measuring.

You can also run them separately:

```bash
# C reference only
gcc -O2 -o /tmp/bench benchmark/benchmark_c.c && /tmp/bench

# Kotlin JVM only
./gradlew -q :benchmark:jvmRun
```

### What the benchmark measures

The C benchmark (`benchmark/benchmark_c.c`) runs each 128-bit operation in a tight loop using `__int128` compiled with `gcc -O2`. This is the performance floor — pure register arithmetic, no language overhead. A `volatile` sink prevents dead code elimination.

The Kotlin benchmark (`benchmark/src/jvmMain/.../BenchmarkJvm.kt`) runs the **same operations with the same operand values** in three tiers:

1. **Scalar** — Raw `Long` pairs with inline arithmetic (no `Int128` objects). This simulates what Kotlin multi-field value classes (MFVC) will provide when stabilized. The difference between C and scalar is the pure instruction overhead: MethodHandle dispatch, carry detection patterns, JVM loop mechanics.

2. **Int128** — The real library API. Each `+`, `*`, etc. returns a new `Int128` heap object. The difference between scalar and Int128 is the allocation overhead: ~7ns per `new Int128(hi, lo)` on HotSpot.

3. **Int128Array** — Bulk operations on a flat `LongArray` backing. Shows the cost when storage is pre-allocated but each `get`/`set` still creates a temporary `Int128`.

The comparison script (`benchmark/run-benchmarks.sh`) parses both outputs and prints three tables:
- **Instruction cost** — C vs Kotlin scalar (how good is the JIT?)
- **Allocation cost** — Scalar vs Int128 (what does heap allocation add?)
- **Full comparison** — All four tiers side by side with C-to-Int128 ratios

### Results

### Arithmetic — instruction cost only (scalar Longs vs C)

| Operation | C `__int128` | Kotlin scalar | Ratio | What the gap is |
|-----------|-------------|---------------|-------|----------------|
| add | <0.3 ns | 0.58 ns | ~2x | Carry detection pattern vs hardware `adc`. Both compile to `add`+`adc`, but JVM loop overhead differs. |
| multiply | 2.82 ns | 3.52 ns | **1.2x** | `Math.multiplyHigh` intrinsic nearly matches C `mul`. |
| multiply-high | 0.99 ns | 2.70 ns | 2.7x | MethodHandle `invokeExact` dispatch. Direct `invokestatic` would be ~1.0x but breaks Android D8. |
| shift | 2.44 ns | 4.06 ns | 1.7x | Cross-word shift is 4 Long ops vs C's 2-insn `shld`/`shrd`. |
| compare | 1.03 ns | 2.04 ns | 2.0x | Two Long comparisons + branch vs C's `cmp`+`sbb`. |

**Takeaway**: The raw arithmetic is within **1.2-2.7x** of C. The MethodHandle dispatch for multiply-high (2.7x) is the largest gap and exists solely to work around Android D8 desugaring. Multiply itself is 1.2x — essentially hardware speed.

### Allocation overhead — Int128 objects vs scalar

| Operation | Kotlin scalar | Kotlin `Int128` | Overhead | Why |
|-----------|--------------|-----------------|----------|-----|
| add | 0.58 ns | 7.53 ns | 13x | Each `+` allocates a new `Int128` object (~7ns alloc) |
| multiply | 3.52 ns | 8.34 ns | 2.4x | One alloc per `*` (mul cost dominates, alloc is smaller fraction) |
| shift | 4.06 ns | 54.66 ns | 13x | `(a shl 7) xor (a ushr 3)` creates 3 intermediate objects |
| compare | 2.04 ns | 22.99 ns | 11x | Array `get` creates temporary `Int128` per comparison |
| mixed (mul-accum) | — | 11.87 ns | — | Realistic: one mul + one add + occasional mod |

**Takeaway**: Object allocation is the dominant cost, not arithmetic. The multiply instruction is 3.52ns; wrapping it in an `Int128` object adds ~5ns. For add, the instruction is 0.58ns but the object costs ~7ns. **Kotlin MFVC will eliminate this gap entirely** — `Int128` would become two register-passed Longs with zero allocation.

### Division — the largest C vs Kotlin gap

| Operation | C `__int128` | Kotlin `Int128` | Ratio | Why |
|-----------|-------------|-----------------|-------|-----|
| div by small (÷7) | 15.62 ns | 780 ns | 50x | C uses optimized `__divti3` runtime; Kotlin uses O(128) bit-at-a-time loop |
| div by large (÷3×2^64+7) | 4.88 ns | 48.94 ns | 10x | Kotlin loop is shorter when quotient is small |

Division is the weakest operation on JVM. The pure-Kotlin `SoftwareArithmetic.unsignedDivRem` is an O(128) binary long-division loop — correct but slow. On native targets, this is bypassed entirely via cinterop to `__divti3`.

### Int128Array — avoiding allocation in bulk

| Operation | `Int128` (per-op alloc) | `Int128Array` (flat backing) | Improvement |
|-----------|------------------------|------------------------------|-------------|
| add | 7.53 ns | 11.39 ns | Similar (array access overhead offsets alloc savings) |
| multiply | 8.34 ns | 11.10 ns | Similar |

`Int128Array` stores values in a contiguous `LongArray` — no per-element object headers. The `get`/`set` still create temporary `Int128` objects, so the per-operation numbers are similar. The real benefit is for **storage**: an `Int128Array(1_000_000)` is one `long[2_000_000]` allocation (16 MB) vs. one million `Int128` objects (32 MB + GC pressure). The cinterop batch functions (`int128_batch_add`, `int128_batch_mul`) can process the flat array in a single native call on Kotlin/Native targets.

### What Kotlin MFVC will change

When Kotlin multi-field value classes ship, `Int128(hi, lo)` will be erased to two `Long` parameters at call sites — no heap allocation. Expected impact:

| Operation | Current `Int128` | Expected with MFVC | C `__int128` |
|-----------|-----------------|-------------------|-------------|
| add | 7.53 ns | ~0.6 ns | <0.3 ns |
| multiply | 8.34 ns | ~3.5 ns | 2.82 ns |
| shift | 54.66 ns | ~4 ns | 2.44 ns |
| compare | 22.99 ns | ~2 ns | 1.03 ns |

## API

### Int128

```kotlin
class Int128(val hi: Long, val lo: Long) : Comparable<Int128> {
    // Arithmetic (wrapping)
    operator fun plus/minus/times/div/rem(other: Int128): Int128
    operator fun unaryMinus(): Int128
    operator fun inc/dec(): Int128

    // Bitwise
    infix fun and/or/xor(other: Int128): Int128
    fun inv(): Int128
    infix fun shl/shr/ushr(bitCount: Int): Int128

    // Conversion
    fun toInt/toLong/toDouble/toFloat/toUInt128()
    fun toString(radix: Int = 10): String

    // Bit utilities
    fun countLeadingZeroBits/countTrailingZeroBits/countOneBits(): Int

    companion object {
        val ZERO, ONE, NEGATIVE_ONE, MAX_VALUE, MIN_VALUE
        fun fromLong(value: Long): Int128
        fun parseString(s: String, radix: Int = 10): Int128
    }
}

// Checked arithmetic (throw on overflow)
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

Same API surface with unsigned semantics. `shr` is always logical (zero-fill). No `unaryMinus`. Division/remainder use unsigned comparison.

### Int128Array / UInt128Array

Flat `LongArray`-backed containers for zero per-element allocation:

```kotlin
val arr = Int128Array(1_000_000)          // single long[] allocation
arr[0] = Int128.fromLong(42)              // no boxing
val v = arr[0]                            // no unboxing allocation
for (elem in arr) { /* iterate */ }       // no iterator allocation per element

val a = int128ArrayOf(Int128.ONE, Int128.fromLong(42))
```

## Verification

Every README claim is machine-verified:

```bash
./gradlew :long128-core:jvmTest   # 187 tests: BigInteger oracle, bytecode checks, tier detection
./verify-instructions.sh           # 19 checks: objdump x86-64 + clang cross-compile ARM64
```

| Test suite | What it proves | Platform |
|------------|---------------|----------|
| `BigIntegerOracleTest` | All ops match `java.math.BigInteger` (500+ pairs per op) | JVM |
| `CrossPlatformOracleTest` | All ops match `ionspin/bignum` BigInteger | All targets |
| `PlatformConsistencyTest` | multiply/divide identical across platforms (1360 pairs) | All targets |
| `IntrinsicTierTest` | MethodHandle resolves, no `invokestatic` in bytecode, no boxing | JVM |
| `BytecodeVerificationTest` | No BigInteger dependency, correct constant pool entries | JVM |
| `verify-instructions.sh` | x86-64: `mul`, `adc`, `sbb`, `__divti3` via objdump; ARM64: `umulh`, `adds`+`adc`, `subs`+`sbc` via clang cross-compile | x86-64 host |

## Building

```bash
./gradlew :long128-core:jvmTest          # JVM tests (all 187)
./gradlew :long128-core:linuxX64Test     # Linux native tests
./gradlew :long128-core:macosArm64Test   # macOS ARM64 native tests
./verify-instructions.sh                  # x86-64 instruction verification
```

## Publishing

```bash
# Local
./gradlew :long128-core:publishToMavenLocal

# Maven Central (requires OSSRH_USERNAME, OSSRH_PASSWORD, SIGNING_KEY, SIGNING_PASSWORD)
./gradlew :long128-core:publishAllPublicationsToSonatypeRepository
```

## License

MIT License — Copyright (c) 2026 Vitor Pamplona
