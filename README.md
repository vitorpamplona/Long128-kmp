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
