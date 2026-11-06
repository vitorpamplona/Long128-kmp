# Long128-kmp

128-bit integers for Kotlin Multiplatform. Kotlin has no `Int128`; C and Rust do (`__int128`, `i128`) and they compile to a handful of machine instructions. This library provides `Int128` and `UInt128` for the JVM, Android, iOS, macOS, Linux and Windows, built so the arithmetic reaches those same instructions: a 64×64→128 multiply is one `mul` / `umulh`, an add is `add` + `adc`.

It is also a measurement project: **how close can Kotlin get to C's `__int128`?** The repo carries a C benchmark, a JMH benchmark that runs the same loops on the JVM, a Kotlin/Native benchmark, and a script that disassembles the compiled code to check which instructions it uses. Answer: C speed on both the JVM and native when hot loops use the mutable accumulators, and within about 1.3-5x with plain values ([numbers](#performance-how-close-to-c)).

## What you get

| | Use it for | Cost per operation |
|---|---|---|
| **`Int128` / `UInt128`** | Everyday 128-bit values: fields, map keys, parsing and printing, arithmetic, comparisons | Native: none, a register value. JVM: one small object per result, often removed by escape analysis |
| **`MutableInt128` / `MutableUInt128`** | Hot loops that carry a running value from one iteration to the next: accumulators, hashes, PRNGs, modular arithmetic | None on either platform: allocated once, updated in place, kept in registers. C speed |
| **`Int128Array` / `UInt128Array`** | Storing many values | One `LongArray` for N values, no per-element objects |
| **`multiplyHigh` / `unsignedMultiplyHigh`** | Your own bignum, elliptic-curve or hash code on plain `Long`s: the high 64 bits of a 64×64 product | One instruction (`mul` / `umulh` / `imul` / `smulh`) |

All types use wrapping arithmetic like `Int` and `Long`, and come with checked arithmetic (`addExact`, `subtractExact`, `negateExact`), parsing and printing in any radix 2-36, and conversions to and from the built-in number types.

### Which one should I use?

- Start with **`Int128` / `UInt128`**. They are the general-purpose types, and on Kotlin/Native they are already allocation-free.
- If a profiler shows a loop that updates a 128-bit value on every iteration (`acc = acc * a + b`), switch that loop's running value to a **`MutableUInt128`**. On the JVM this is the difference between allocating every iteration and running at C speed.
- If you keep thousands of values, keep them in an **`Int128Array`** rather than a `List<Int128>`, which boxes each element.
- If your code already works on `Long` limbs (a field multiply, a hash mix), call **`unsignedMultiplyHigh`** directly.

## Quick start

```kotlin
// build.gradle.kts
dependencies {
    implementation("com.vitorpamplona.long128:long128-core:<version>")
}
```

```kotlin
import com.vitorpamplona.long128.*

// Values
val a = Int128.fromLong(Long.MAX_VALUE)
val product = a * 2.toInt128()                        // full 128-bit result, no overflow
val max = "170141183460469231731687303715884105727".toInt128()   // Int128.MAX_VALUE
val u = UInt128.MAX_VALUE                             // 2^128 - 1
println(u.toString(16))                               // ffffffffffffffffffffffffffffffff

// A hot loop: the running value is a mutable accumulator, allocated once
val acc = MutableUInt128(UInt128.ONE)
val m = UInt128(0L, 1_000_000_007L)
for (x in inputs) {                                    // inputs: List<UInt128>
    acc *= x
    acc %= m
}
val result: UInt128 = acc.toUInt128()

// Many values: one flat LongArray
val arr = Int128Array(1_000_000)
for (i in 0 until arr.size) arr[i] = Int128.fromLong(i.toLong())

// 64×64 → high 64 bits, for code on Long limbs
val hi = unsignedMultiplyHigh(-1L, -1L)               // 0xFFFFFFFFFFFFFFFE
```

**Targets:** JVM (JDK 17+; JDK 19+ for hardware-speed division), Android (the same JVM artifact), and Kotlin/Native `macosArm64`, `macosX64`, `iosArm64`, `iosX64`, `iosSimulatorArm64`, `linuxX64`, `linuxArm64`, `mingwX64`. CI runs the tests on JDK 17 and 21, `linuxX64`, `linuxArm64` (under qemu) and `mingwX64`; the Apple targets build but their CI jobs are not running at the moment. Android's `Math.multiplyHigh` gap below API 31 is handled transparently (see [Platform dispatch](#platform-dispatch)).

## Performance: how close to C?

Short answer: on both the JVM and Kotlin/Native, the arithmetic runs at C speed when the loop's running value is a `MutableInt128`/`MutableUInt128` accumulator, and within 1.4-5x of C with plain `Int128` values. The gap is allocation on the JVM and register moves on native, not the arithmetic.

All numbers: same machine (4-core Xeon @ 2.1 GHz, Linux x86-64), ns per operation, lower is better. **C** is `benchmark/benchmark_c.c` (gcc -O2, `unsigned __int128`); the JVM column is JMH (`benchmark-jmh`, JDK 21, 2 forks × 5 iterations) running the same loops; native is the release `benchmark` binary (median of 5 runs).

| Operation (loop) | C | JVM `UInt128` | JVM mutable | Native `UInt128` | Native mutable |
|---|---|---|---|---|---|
| add `acc += a + i; acc += b` | 0.55 | 2.75 | **0.43** | 1.42 | **0.35** |
| sub | 0.50 | 3.40 | **0.50** | 1.32 | — |
| multiply-add `acc = acc * a + b` | 1.91 | 5.53 | **2.45** | 4.32 | **1.59** |
| multiply-high (raw `Long`s) | 0.64 | **0.63** | — | **0.59** | — |
| shift `(acc shl 7) xor (acc shr 3)` | 1.57 | 2.31 | **1.57** | 3.47 | — |
| compare `a < b; a += 1` | 0.55 | 6.07 | **0.83** | — | — |
| div by 7 | 8.43¹ / 10.57² | 9.70 | **6.84** | 13.04 | **10.92** |
| div by a 128-bit divisor | 2.41 | 9.61 | **5.55** | 3.91 | **3.61** |
| mixed (mul-add, `%` every 256) | 1.60 | 6.10 | **2.20** | 4.50 | **1.64** |
| array multiply `out[i] = x[i] * y[i]` | 1.02 | **1.35** | — | 3.51 | — |

¹ gcc turns `/ 7` by a compile-time constant into a reciprocal multiply. ² The same loop with the divisor read at run time, which is what any library API gets.

### Where the remaining gap is

- **JVM, `Int128`/`UInt128` values carried around a loop: allocation.** On the JVM an `Int128` is one 16-byte object. HotSpot's escape analysis removes it in straight-line code and in loops that do not carry it (the array multiply allocates nothing and runs at 1.3x C), but not when a value flows through a loop's back edge: then every iteration allocates. Only Kotlin multi-field value classes / Valhalla can fix that for an immutable type.
- **The fix you can use today: a mutable accumulator.** `MutableUInt128` / `MutableInt128` are allocated once, before the loop, and updated in place (`acc *= a; acc += b`, `acc /= d`, `acc.multiplyAdd(a, b)`). Escape analysis turns a non-escaping one into two `long` registers, and Kotlin/Native keeps it in registers too, so loops run at C speed with zero allocation (JMH: 0 B/op for every mutable benchmark).
- **Division on the JVM** is Knuth's algorithm D on `Long.divideUnsigned` (one hardware `div` from JDK 19). x86 can divide 128 by 64 bits in one instruction, which Java cannot reach, so a 128/64 step costs two 64-bit divisions: that is the remaining 2.3x on division by a large divisor.
- **Native `Int128` in a loop-carried chain:** the value lives in an SSE register between iterations; each iteration moves it to general registers and back (~6 cycles). With a mutable accumulator the loop is pure general-register code.

### Kotlin/Native: `Int128` is a register value

On native, `Int128` and `UInt128` are value classes over `Vector128`, a 16-byte value type that lives in a SIMD register, so no operation allocates. Multiply-high is a single `mul` (x86-64) / `umulh` (ARM64) inlined into the caller, and division is one `__divti3` call.

1. *No GC state switch.* A normal cinterop call switches the thread's GC state to "native" and back around the C function: two atomic exchanges, ~11 ns, six times the cost of the multiply. The library binds its C helpers with `@GCUnsafeCall`, the annotation the Kotlin/Native stdlib uses for its own small runtime functions, which calls the symbol directly. The cinterop C code is linked into the program as LLVM bitcode, so LLVM inlines it. For that to happen on ARM64, `int128.def` compiles the C helpers for the same CPU the backend uses for Kotlin code: LLVM refuses to inline across mismatched CPU feature sets.
2. *No allocation.* A heap `Int128` cost ~11 ns per result on Kotlin/Native. `Int128` is a value class over platform storage (`Bits128`): `Vector128` on native, built from two 64-bit words by a C helper (Kotlin can only build one from four `Int`s, which compiles to a chain of shuffles).
3. *Guaranteed inlining where LLVM declines.* LLVM inlines `plus`, `minus` and the shifts on its own but not `times`, the `hi`/`lo` accessors, or `Int128Array.get`/`set`, so those are Kotlin `inline` functions. Inlining every operator was measured too: it gained nothing more on native and slowed the JVM.

`verify-instructions.sh` checks the linked binary on x86-64 and ARM64.

`GCUnsafeCall` is a Kotlin/Native internal API (opt-in `kotlin.native.internal.InternalForKotlinNative`, set in `long128-core/build.gradle.kts`). It is safe only for short, non-blocking C functions that never call back into Kotlin, which is all these helpers do. A klib carries the annotation and the consumer's compiler lowers it, so a future Kotlin release that changes it affects apps using the library at link time. [KT-85635](https://youtrack.jetbrains.com/issue/KT-85635) asks for a supported intrinsic.

### Running the benchmarks

```bash
./benchmark/run-benchmarks.sh                    # C + quick JVM harness + Native (host target), side by side
./gradlew :benchmark-jmh:jvmBenchmark            # JMH, the JVM numbers above (~10 min)
./gradlew :benchmark-jmh:jvmQuickBenchmark       # JMH, shorter; -PjmhInclude=div to filter

# Or separately:
gcc -O2 -o /tmp/bench benchmark/benchmark_c.c && /tmp/bench
./gradlew -q :benchmark:runReleaseExecutableLinuxX64   # or MacosArm64 / MacosX64 / LinuxArm64
```

For allocation per operation, run the JMH jar with the GC profiler: `java -jar benchmark-jmh/build/benchmarks/jvm/jars/*.jar -prof gc` (after `./gradlew :benchmark-jmh:jvmBenchmarkJar`).

The C benchmark keeps gcc honest: an empty `asm` barrier stops it folding the add/sub loops into a closed form, and the `*_rt` division variants read the divisor at run time. `benchmark-jmh` mirrors each C loop (same constants, same dependency chain); `benchmark/` is the older single-shot JVM harness plus the native benchmark.

## How it works

### Representation

`Int128` and `UInt128` hold two 64-bit words: `hi` (most-significant 64 bits) and `lo` (least-significant 64 bits, treated as unsigned). The value is `hi × 2^64 + lo.toULong()`.

Both are value classes over platform storage, `Bits128` (`internal/Bits128.kt`):

| Platform | Storage | An `Int128` costs |
|----------|---------|-------------------|
| Native | `kotlinx.cinterop.Vector128` | Nothing: a register value, passed and returned in `xmm`/`q` registers |
| JVM | a final class with two `Long` fields | One small object, as before; often removed by escape analysis |

Like any value class, an `Int128` is boxed where a value class must be: as `Any`, as a nullable `Int128?`, or as a generic type argument (`List<Int128>`, `Comparable<Int128>`). Use `Int128Array` for collections.

Both types use wrapping overflow semantics, matching Kotlin's built-in `Int` and `Long`. `Int128.MAX_VALUE + Int128.ONE == Int128.MIN_VALUE`.

### Platform dispatch

Expensive operations (multiply, divide) go through `expect`/`actual` functions in `PlatformIntrinsics`. Cheap operations (add, subtract, bitwise, shifts) are pure Kotlin in `commonMain` because the compiler already generates optimal code for them.

**JVM** (requires JDK 17+; JDK 19+ for hardware-speed division) — `PlatformIntrinsics.jvm.kt` resolves `Math.multiplyHigh` and `Math.unsignedMultiplyHigh` (JDK 18+) via `MethodHandle` at class-load time. MethodHandle is used instead of `invokestatic` because Android's D8 desugarer replaces direct `Math.multiplyHigh` calls with a software fallback when the app's `minSdk < 31`. HotSpot C2 inlines constant MethodHandle fields, so desktop JVM pays no penalty. Division is Knuth's algorithm D on `java.lang.Long.divideUnsigned`, which HotSpot compiles to one hardware `div` from JDK 19 (Kotlin's own `ULong` division is a software routine the JIT does not recognize).

**Native** — `PlatformIntrinsics.native.kt` binds C functions compiled from `__int128` (`int128.def`) with `@GCUnsafeCall`, so there is no cinterop GC state switch and LLVM inlines the C body into the Kotlin caller (see [Kotlin/Native](#kotlinnative-int128-is-a-register-value)). In the linked binary:

| Operation | x86-64 | ARM64 |
|-----------|--------|-------|
| multiply-high | `mul` (1 insn, inline) | `umulh` (1 insn, inline) |
| full 128×128 mul | `mul` + 2 `imul` + 2 `add` | `umulh` + 2 `madd` + `mul` |
| add | `add`+`adc` | `adds`+`adc` |
| subtract | `sub`+`sbb` | `subs`+`sbc` |
| divide + remainder | one `call __divti3` | one `call __divti3` |

The full multiply is Kotlin code (`multiply128High`) on top of the multiply-high, shared by every platform. Division asks C for each 64-bit word of the result separately, so every word comes back in a register; after inlining, LLVM merges the two into one `__divti3` call (a call site that needs only the remainder gets one `__modti3`). Add and subtract are pure Kotlin: the carry detection pattern `(result.toULong() < operand.toULong())` compiles to the same `add`+`adc` / `adds`+`adc` as C.

**Android** — Same JVM implementation. On API 31+, MethodHandle resolves `Math.multiplyHigh` and ART intrinsifies it to `SMULH` on ARM64. On API <31, falls back to Karatsuba (4-multiply decomposition). `Math.unsignedMultiplyHigh` was never added to Android, so we use the signed-to-unsigned correction on all API levels.

### Project structure

```
long128-core/src/
├── commonMain/kotlin/.../
│   ├── Int128.kt                     # Signed 128-bit integer
│   ├── UInt128.kt                    # Unsigned 128-bit integer
│   ├── Int128Array.kt                # Flat LongArray-backed arrays
│   ├── MutableInt128.kt              # MutableInt128 / MutableUInt128 accumulators
│   ├── MultiplyHigh.kt               # Public multiplyHigh / unsignedMultiplyHigh
│   └── internal/
│       ├── Bits128.kt                # expect: the 128-bit storage behind Int128/UInt128
│       ├── PlatformIntrinsics.kt     # expect: unsignedMultiplyHigh, signedQuotient128, ...
│       └── SoftwareArithmetic.kt     # Pure-Kotlin: Knuth division, toString, parsing
├── jvmMain/.../internal/
│   ├── Bits128.jvm.kt                # actual: a final class with two Long fields
│   └── PlatformIntrinsics.jvm.kt     # actual: MethodHandle → Math.multiplyHigh; Long.divideUnsigned
├── nativeMain/.../internal/
│   ├── Bits128.native.kt             # actual: typealias to Vector128 (a register value)
│   └── PlatformIntrinsics.native.kt  # actual: @GCUnsafeCall → __int128, inlined
└── nativeInterop/cinterop/
    └── int128.def                    # C: __int128 multiply-high, div/rem, Vector128 builder

benchmark/          C reference (benchmark_c.c), native benchmark, quick JVM harness, run-benchmarks.sh
benchmark-jmh/      JMH twins of the C loops (JVM)
examples/           Runnable API tour (./gradlew :examples:jvmRun)
```

## API

### Int128

```kotlin
value class Int128 : Comparable<Int128> {
    constructor(hi: Long, lo: Long)
    val hi: Long
    val lo: Long

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
        const val SIZE_BITS = 128; const val SIZE_BYTES = 16
        fun fromLong(value: Long): Int128
        fun fromInt(value: Int): Int128
        fun parseString(s: String, radix: Int = 10): Int128     // throws NumberFormatException
        fun parseStringOrNull(s: String, radix: Int = 10): Int128?
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
fun String.toInt128OrNull(radix: Int = 10): Int128?
fun maxOf(a: Int128, b: Int128): Int128
fun minOf(a: Int128, b: Int128): Int128
```

Division truncates toward zero and the remainder takes the dividend's sign, like `Long`. `MIN_VALUE / -1` throws `ArithmeticException` (the result does not fit); `MIN_VALUE % -1` is `0`.

### Multiply-high

The upper 64 bits of a 64×64-bit product, one instruction on every target: the building block for bignum, elliptic-curve field and hash arithmetic. Same contracts as `java.lang.Math`.

```kotlin
fun multiplyHigh(x: Long, y: Long): Long            // signed:   imul / smulh
fun unsignedMultiplyHigh(x: Long, y: Long): Long    // unsigned: mul  / umulh
fun ULong.multiplyHigh(other: ULong): ULong
```

On Kotlin/Native these inline into the caller's code across module boundaries, so a hot loop in your module gets a bare `mul` / `umulh`.

### MutableInt128 / MutableUInt128

In-place accumulators for loops that carry a 128-bit value from one iteration to the next. Allocated once, they cost nothing per operation: on the JVM, escape analysis keeps a non-escaping one in two registers; on native it stays in registers too.

```kotlin
val acc = MutableUInt128(UInt128.ONE)
for (x in inputs) {
    acc *= x            // timesAssign
    acc += offset       // plusAssign
    if (acc >= limit) acc %= modulus
}
val result: UInt128 = acc.toUInt128()

// also: -=, /=, multiplyAdd(a, b), andAssign/orAssign/xorAssign, shiftLeft/shiftRight,
//       negate() (MutableInt128), set(value), compareTo(value)
```

### UInt128

Same API with unsigned semantics. `shr` is always logical (zero-fill). No `unaryMinus` or `negateExact`. Extra conversions: `UInt128.fromULong`, `UInt.toUInt128()`, `ULong.toUInt128()`, `toUInt()`/`toULong()`.

### Int128Array / UInt128Array

Flat `LongArray`-backed containers: one allocation for N elements, and no per-element box (a `List<Int128>` boxes each element). `get`/`set` never allocate on native.

```kotlin
val arr = Int128Array(1_000_000)     // one long[2_000_000] — 16 MB
arr[0] = Int128.fromLong(42)
for (i in 0 until arr.size) { /* arr[i] ... */ }   // no boxing; `for (elem in arr)` boxes each element
val small = int128ArrayOf(listOf(a, b, c))          // Kotlin forbids `vararg` of a value class
val copy = listOf(a, b, c).toInt128Array()
```

## Verification

The correctness, instruction and allocation claims in this README are checked by tests and scripts in the repo (on Linux and Windows in CI; the Apple targets are not run in CI at the moment):

```bash
./gradlew :long128-core:jvmTest   # 293 tests (260 on linuxX64Test)
./verify-instructions.sh           # 27 instruction checks with both Linux benchmark binaries built
```

| What | How | Result |
|------|-----|--------|
| Arithmetic correctness | BigInteger oracle (java.math + ionspin/bignum), 500+ value pairs per op, all platforms | 293 JVM / 260 native tests pass |
| Division corner cases | `DivisionTest`: every operand bit length, 32/64-bit edges, quotient-digit corrections, every radix | Same results as the oracle on JVM (Knuth D) and native (`__udivti3`) |
| `Math.multiplyHigh` used | Bytecode inspection of compiled `.class` | Constant pool contains MethodHandle + `multiplyHigh` |
| No boxing | Bytecode inspection | No `Long.valueOf` in any library class |
| No allocation in accumulator loops | JMH `-prof gc` | 0 B/op for every `MutableUInt128` / `MutableInt128` benchmark |
| No BigInteger dependency | Bytecode + class file scan | Zero references |
| x86-64 `mul` instruction | `objdump -d` of compiled C interop | `mul rsi` (1 insn) |
| x86-64 `add`+`adc` | `objdump -d` | Carry flag, no branch |
| ARM64 `umulh` | `clang --target=aarch64` cross-compile + inspect `.s` | `umulh` (1 insn) |
| ARM64 `adds`+`adc` | Same cross-compile | Carry flag, no branch |
| Kotlin carry pattern → `adc` | Compile equivalent C pattern, compare objdump | Identical output |
| Native multiply-high inlined | Disassemble the linked benchmark binary (x86-64 and ARM64) | Bare `mul` / `umulh` in Kotlin code, incl. a loop in another module |
| No GC state switch | Same binary: atomic exchange within 16 insns of a multiply-high, cinterop bridge calls | None (the pre-`@GCUnsafeCall` binary fails this check) |
| One division call per result | Same binary: back-to-back division calls | None: the per-word C helpers merge into one `__divti3` / `__udivti3` / `__modti3` |

## Building

Requires JDK 17 or newer. CI runs the JVM test suite against JDK 17 and 21.

```bash
./gradlew :long128-core:jvmTest          # JVM tests
./gradlew :long128-core:linuxX64Test     # Linux native tests
./gradlew :long128-core:macosArm64Test   # macOS ARM64 tests
./verify-instructions.sh                  # Instruction verification
./benchmark/run-benchmarks.sh             # C vs Kotlin JVM vs Kotlin/Native benchmark
./gradlew :benchmark-jmh:jvmBenchmark     # JMH (JVM)
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
| JVM: `Int128` carried around a loop allocates per iteration | Kotlin MFVC not stable; escape analysis stops at loop back edges | `MutableInt128`/`MutableUInt128` accumulators (C speed, 0 allocation) or `Int128Array` |
| `Int128` is a value class | Needed for allocation-free native code | No `===`; `int128ArrayOf(listOf(...))` instead of `vararg`; Java callers see mangled names (`plus-FHhI208`) |
| JVM division by a 128-bit divisor is ~2.3x C | x86's 128/64 `div` is unreachable from Java; Knuth D needs two 64-bit divisions | Division by a 64-bit divisor matches C |
| Android API <31: no hardware multiply | `Math.multiplyHigh` absent | Karatsuba fallback (3-8x faster than BigInteger) |
| Android: no `unsignedMultiplyHigh` | Never added to Android SDK | Signed + 3-insn correction |
| Native: ~2x C in loop-carried `Int128` chains | The carried value lives in an SSE register; ~6 cycles of moves per iteration | `MutableInt128`/`MutableUInt128` accumulators run at C speed |
| Native relies on `GCUnsafeCall` | Kotlin/Native internal API; no public intrinsic yet ([KT-85635](https://youtrack.jetbrains.com/issue/KT-85635)) | CI builds and checks the linked binary |
| Apple targets not run in CI | The macOS CI jobs are currently cancelled before they start | Run `./gradlew :long128-core:macosArm64Test` on a Mac |
| Android performance not measured | JMH measures desktop HotSpot; ART's JIT and escape analysis differ | The mutable accumulators avoid per-iteration allocation by construction, with or without escape analysis |

## License

MIT — Copyright (c) 2026 Vitor Pamplona
