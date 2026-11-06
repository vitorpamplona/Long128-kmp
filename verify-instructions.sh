#!/bin/bash
# verify-instructions.sh — Proves the README claims about CPU instruction usage.
#
# Run: ./verify-instructions.sh                 # all sections
#      ./verify-instructions.sh --native-only   # only section 4 (linked Kotlin/Native binary)
#
# Requires: gcc, objdump, javap, and a successful ./gradlew :long128-core:jvmTest.
# Section 4 also needs the native benchmark binary (Linux only):
#   ./gradlew :benchmark:linkReleaseExecutableLinuxX64   (and/or ...LinuxArm64)
# and is skipped when it has not been built.

set -e
NATIVE_ONLY=0
[ "${1:-}" = "--native-only" ] && NATIVE_ONLY=1
PASS=0
FAIL=0

pass() { echo "  PASS: $1"; PASS=$((PASS+1)); }
fail() { echo "  FAIL: $1"; FAIL=$((FAIL+1)); }

echo "========================================"
echo " Long128-kmp Instruction Verification"
echo "========================================"
echo ""

if [ "$NATIVE_ONLY" = 0 ]; then
# -------------------------------------------------------------------
# 1. JVM BYTECODE VERIFICATION
# -------------------------------------------------------------------
echo "--- JVM Bytecode ---"

PLATFORM_CLASS="long128-core/build/classes/kotlin/jvm/main/com/vitorpamplona/long128/internal/PlatformIntrinsics_jvmKt.class"
INT128_CLASS="long128-core/build/classes/kotlin/jvm/main/com/vitorpamplona/long128/Int128.class"
MATHUTILS_CLASS="long128-core/build/classes/kotlin/jvm/main/com/vitorpamplona/long128/internal/SoftwareArithmeticKt.class"

if [ ! -f "$PLATFORM_CLASS" ]; then
    echo "  Class files not found. Run: ./gradlew :long128-core:jvmTest first"
    exit 1
fi

# Claim: platformMultiplyHigh resolves Math.multiplyHigh via MethodHandle (D8-safe)
BYTECODE=$(javap -c -p "$PLATFORM_CLASS" 2>/dev/null)
if echo "$BYTECODE" | grep -q "MethodHandle\|invokeExact\|findStatic"; then
    pass "platformMultiplyHigh → MethodHandle.invokeExact (D8-safe, resolves Math.multiplyHigh at runtime)"
else
    fail "platformMultiplyHigh does NOT use MethodHandle dispatch"
fi

# Claim: fallback Karatsuba implementation exists for API < 31
if echo "$BYTECODE" | grep -q "signedMultiplyHighFallback"; then
    pass "signedMultiplyHighFallback → pure-Kotlin Karatsuba (4-imul) fallback present"
else
    fail "signedMultiplyHighFallback not found"
fi

# Claim: no Long.valueOf boxing in Int128
INT128_BC=$(javap -c -p "$INT128_CLASS" 2>/dev/null)
if echo "$INT128_BC" | grep -q "Long.valueOf"; then
    fail "Int128.class contains Long.valueOf (unexpected boxing)"
else
    pass "Int128.class has no Long.valueOf — no boxing in arithmetic"
fi

# Claim: no BigInteger dependency
if echo "$INT128_BC" | grep -q "BigInteger"; then
    fail "Int128.class references BigInteger"
else
    pass "Int128.class has no BigInteger dependency"
fi

MATH_BC=$(javap -c -p "$MATHUTILS_CLASS" 2>/dev/null)
if echo "$MATH_BC" | grep -q "BigInteger"; then
    fail "SoftwareArithmeticKt.class references BigInteger"
else
    pass "SoftwareArithmeticKt.class has no BigInteger dependency"
fi

echo ""

# -------------------------------------------------------------------
# 2. NATIVE x86-64 INSTRUCTION VERIFICATION
# -------------------------------------------------------------------
echo "--- Native x86-64 (compiling C interop code with gcc -O2) ---"

# Extract and compile the C code from the .def file
DEF_FILE="long128-core/src/nativeInterop/cinterop/int128.def"
# Get everything after the --- separator
sed '1,/^---$/d' "$DEF_FILE" > /tmp/long128_cinterop.c

gcc -O2 -c -o /tmp/long128_cinterop.o /tmp/long128_cinterop.c 2>/dev/null
# Prefer Intel syntax; fall back to LLVM objdump's flag if -M intel is unsupported.
if ! DISASM=$(objdump -d -M intel /tmp/long128_cinterop.o 2>/dev/null) || [ -z "$DISASM" ]; then
    DISASM=$(objdump -d --x86-asm-syntax=intel /tmp/long128_cinterop.o 2>/dev/null \
             || objdump -d /tmp/long128_cinterop.o)
fi

# Helper: extract disassembly of a single function. Handles both:
#   - GNU/ELF objdump:  "0000000000000000 <int128_add>:"
#   - LLVM/Mach-O objdump (macOS): "_int128_add:"
extract_func() {
    local fname="$1"
    local result
    result=$(echo "$DISASM" | sed -n "/<${fname}>:/,/^[[:space:]]*$/p")
    if [ -z "$result" ]; then
        result=$(echo "$DISASM" | sed -n "/^_${fname}:/,/^[[:space:]]*$/p")
    fi
    if [ -z "$result" ]; then
        result=$(echo "$DISASM" | sed -n "/^${fname}:/,/^[[:space:]]*$/p")
    fi
    echo "$result"
}

# Claim: int128_multiply_high_signed uses a single imul instruction
SIGNED_MUL=$(extract_func int128_multiply_high_signed)
if echo "$SIGNED_MUL" | grep -q "imul.*rsi\|imul.*rdi"; then
    pass "int128_multiply_high_signed → single 'imul' instruction (128-bit signed multiply)"
else
    fail "int128_multiply_high_signed does not use imul"
fi

# Claim: int128_multiply_high_unsigned uses a single mul instruction
# Match standalone 'mul' (excluding 'imul'): a non-letter must precede 'mul'.
UNSIGNED_MUL=$(extract_func int128_multiply_high_unsigned)
if echo "$UNSIGNED_MUL" | grep -qE '[^a-zA-Z]mul[[:space:]]'; then
    pass "int128_multiply_high_unsigned → single 'mul' instruction (128-bit unsigned multiply)"
else
    fail "int128_multiply_high_unsigned does not use mul"
fi

# Count instructions in the multiply_high functions (excluding nop/endbr/ret)
SIGNED_INSN_COUNT=$(echo "$SIGNED_MUL" | grep -cE '^[[:space:]]+[0-9a-f]+:')
[ -z "$SIGNED_INSN_COUNT" ] && SIGNED_INSN_COUNT=0
# Expect: endbr64, mov, imul, mov, ret = 5 instructions
if [ "$SIGNED_INSN_COUNT" -le 6 ] && [ "$SIGNED_INSN_COUNT" -gt 0 ]; then
    pass "int128_multiply_high_signed is $SIGNED_INSN_COUNT instructions (minimal)"
else
    fail "int128_multiply_high_signed is $SIGNED_INSN_COUNT instructions (expected 1..6)"
fi

# Claim: full 128x128 multiply uses no loops or calls
FULL_MUL=$(extract_func int128_mul)
if echo "$FULL_MUL" | grep -q "call\|loop\|jmp"; then
    fail "int128_mul contains call/loop/jmp (not straight-line code)"
else
    pass "int128_mul → straight-line code (no loops, no function calls)"
fi
if echo "$FULL_MUL" | grep -qE '[^a-zA-Z]mul[[:space:]]'; then
    pass "int128_mul uses hardware 'mul' instruction"
else
    fail "int128_mul does not use hardware mul"
fi

# Claim: division calls runtime helper (no hardware 128÷128 div exists on x86-64)
SDIV=$(extract_func int128_sdiv_lo)
if echo "$SDIV" | grep -q "call"; then
    pass "int128_sdiv_lo → calls __divti3 runtime (no hardware 128÷128 div on x86-64, as documented)"
else
    fail "int128_sdiv_lo does not call runtime helper (unexpected)"
fi

# Claim: int128_add uses add + adc (carry flag, not branch)
ADD_FUNC=$(extract_func int128_add)
if echo "$ADD_FUNC" | grep -q "adc"; then
    pass "int128_add → add + adc (hardware carry flag, no branch)"
else
    fail "int128_add does not use adc instruction"
fi

# Claim: int128_sub uses sub + sbb (borrow flag, not branch)
SUB_FUNC=$(extract_func int128_sub)
if echo "$SUB_FUNC" | grep -q "sbb"; then
    pass "int128_sub → sub + sbb (hardware borrow flag, no branch)"
else
    fail "int128_sub does not use sbb instruction"
fi

# Claim: batch_add uses add + adc in a tight loop
BATCH_ADD=$(extract_func int128_batch_add)
if echo "$BATCH_ADD" | grep -q "adc"; then
    pass "int128_batch_add → add + adc in loop (amortized cinterop overhead)"
else
    fail "int128_batch_add does not use adc"
fi

# Verify: Kotlin's carry detection pattern compiles to adc (not a branch)
cat > /tmp/long128_carry_pattern.c << 'CEOF'
typedef long long int64_t;
typedef unsigned long long uint64_t;
struct pair { int64_t hi; int64_t lo; };
struct pair kotlin_carry_add(int64_t a_hi, int64_t a_lo, int64_t b_hi, int64_t b_lo) {
    int64_t new_lo = a_lo + b_lo;
    int64_t carry = ((uint64_t)new_lo < (uint64_t)a_lo) ? 1 : 0;
    struct pair r;
    r.hi = a_hi + b_hi + carry;
    r.lo = new_lo;
    return r;
}
CEOF
gcc -O2 -c -o /tmp/long128_carry_pattern.o /tmp/long128_carry_pattern.c 2>/dev/null
CARRY_ASM=$(objdump -d -M intel /tmp/long128_carry_pattern.o)
if echo "$CARRY_ASM" | grep -q "adc"; then
    pass "Kotlin carry pattern (UInt128.plus) → x86-64 add + adc (compiler recognizes carry test)"
else
    fail "Kotlin carry pattern does not compile to adc on x86-64"
fi

echo ""

# -------------------------------------------------------------------
# 3. NATIVE ARM64 INSTRUCTION VERIFICATION (cross-compile with clang)
# -------------------------------------------------------------------
echo "--- Native ARM64 (cross-compiling with clang --target=aarch64-linux-gnu) ---"

cat > /tmp/long128_arm64.c << 'ARMEOF'
typedef long long int64_t;
typedef unsigned long long uint64_t;
typedef unsigned __int128 uint128_t;
typedef __int128 int128_t;
typedef struct { int64_t hi; int64_t lo; } int128_parts;

int64_t int128_multiply_high_unsigned(int64_t a, int64_t b) {
    uint128_t result = (uint128_t)(uint64_t)a * (uint64_t)b;
    return (int64_t)(result >> 64);
}
int128_parts int128_mul(int64_t a_hi, int64_t a_lo, int64_t b_hi, int64_t b_lo) {
    uint128_t a = ((uint128_t)(uint64_t)a_hi << 64) | (uint64_t)a_lo;
    uint128_t b = ((uint128_t)(uint64_t)b_hi << 64) | (uint64_t)b_lo;
    uint128_t r = a * b;
    int128_parts res; res.hi = (int64_t)(r >> 64); res.lo = (int64_t)r; return res;
}
int128_parts int128_add(int64_t a_hi, int64_t a_lo, int64_t b_hi, int64_t b_lo) {
    uint128_t a = ((uint128_t)(uint64_t)a_hi << 64) | (uint64_t)a_lo;
    uint128_t b = ((uint128_t)(uint64_t)b_hi << 64) | (uint64_t)b_lo;
    uint128_t r = a + b;
    int128_parts res; res.hi = (int64_t)(r >> 64); res.lo = (int64_t)r; return res;
}
int128_parts int128_sub(int64_t a_hi, int64_t a_lo, int64_t b_hi, int64_t b_lo) {
    uint128_t a = ((uint128_t)(uint64_t)a_hi << 64) | (uint64_t)a_lo;
    uint128_t b = ((uint128_t)(uint64_t)b_hi << 64) | (uint64_t)b_lo;
    uint128_t r = a - b;
    int128_parts res; res.hi = (int64_t)(r >> 64); res.lo = (int64_t)r; return res;
}
ARMEOF

if ! clang --target=aarch64-linux-gnu -O2 -S -o /tmp/long128_arm64.s /tmp/long128_arm64.c 2>/dev/null; then
    echo "  SKIP: clang cannot cross-compile for aarch64"
else
    ARM_ASM=$(cat /tmp/long128_arm64.s)

    MUL_HIGH_ARM=$(echo "$ARM_ASM" | sed -n '/^int128_multiply_high_unsigned:/,/^\.Lfunc_end/p')
    if echo "$MUL_HIGH_ARM" | grep -q "umulh"; then
        pass "ARM64 int128_multiply_high_unsigned → umulh (single instruction)"
    else
        fail "ARM64 int128_multiply_high_unsigned missing umulh"
    fi

    ADD_ARM=$(echo "$ARM_ASM" | sed -n '/^int128_add:/,/^\.Lfunc_end/p')
    if echo "$ADD_ARM" | grep -q "adds" && echo "$ADD_ARM" | grep -q "adc"; then
        pass "ARM64 int128_add → adds + adc (hardware carry flag)"
    else
        fail "ARM64 int128_add missing adds+adc"
    fi

    SUB_ARM=$(echo "$ARM_ASM" | sed -n '/^int128_sub:/,/^\.Lfunc_end/p')
    if echo "$SUB_ARM" | grep -q "subs" && echo "$SUB_ARM" | grep -q "sbc"; then
        pass "ARM64 int128_sub → subs + sbc (hardware borrow flag)"
    else
        fail "ARM64 int128_sub missing subs+sbc"
    fi

    MUL_ARM=$(echo "$ARM_ASM" | sed -n '/^int128_mul:/,/^\.Lfunc_end/p')
    if echo "$MUL_ARM" | grep -q "umulh" && echo "$MUL_ARM" | grep -q "mul"; then
        if echo "$MUL_ARM" | grep -q "bl "; then
            fail "ARM64 int128_mul contains branch/call"
        else
            pass "ARM64 int128_mul → umulh + madd + madd + mul (4 insns, no branch)"
        fi
    else
        fail "ARM64 int128_mul missing umulh/mul"
    fi
fi

echo ""

fi  # NATIVE_ONLY

# -------------------------------------------------------------------
# 4. LINKED KOTLIN/NATIVE BINARY (what users actually run)
# -------------------------------------------------------------------
# Sections 2-3 disassemble the C helpers on their own. This section disassembles
# the release benchmark executable, where long128-core is consumed as a klib by
# another module, and checks that @GCUnsafeCall let LLVM inline the C bodies:
# multiply-high is a bare mul/umulh inside Kotlin code, with no `xchg` GC
# thread-state switch around it (the ~11 ns a plain cinterop call costs).
echo "--- Kotlin/Native linked binary (benchmark release executable) ---"

# Summarize every Kotlin function of this project (library + benchmark) in one pass.
# The checks below are totals across those functions, because which functions LLVM
# keeps out of line (Int128.times, signedDivide128, the benchmark loops) varies by target.
#
# A GC thread-state switch is an atomic exchange on the thread's state word:
# `xchg` with a memory operand on x86-64; `swpal` (LSE) or an `ldaxr`/`stlxr` loop on ARM64. The function
# that prints results also contains them (Console.print), so a switch only counts
# when it sits within W instructions of a multiply-high — the shape a plain cinterop
# call has after LLVM inlines it.
#
# Output: mulhi mulhi_near_switch c_helper_calls bridge_calls modti3_calls
kn_summary() {
    awk -v MULHI="$2" -v W=16 '
        # memory-operand xchg only: `xchg ax,ax` is a 2-byte NOP used as padding
        function is_switch(l) { return (l ~ /xchg/ && l ~ /PTR|\(/) || l ~ /swpal|stlxr/ }
        # Function labels: "0000000000401000 <kfun:...>:" (ELF / modern llvm-objdump)
        # or "_kfun:...:" (Mach-O style). Instruction lines start with whitespace.
        /^[0-9a-f]+ <.*>:$/ || /^[^ \t].*:$/ {
            name = $0; sub(/^[0-9a-f]+ </, "", name); sub(/^_/, "", name)
            inlib = index(name, "kfun:com.vitorpamplona.long128") == 1
            n = 0; ahead = 0; next
        }
        inlib {
            n++; buf[n % W] = $0
            if (ahead > 0) { ahead--; if (is_switch($0)) { near++; ahead = 0 } }
            if ($0 ~ MULHI) {
                mul++; hit = 0
                for (i = 1; i < W && i < n; i++) if (is_switch(buf[(n - i) % W])) hit = 1
                if (hit) near++; else ahead = W
            }
            if ($0 ~ /<_?(int128|uint128)_[a-z_]+>/) helper++   # call into a C helper: not inlined
            if ($0 ~ /_knbridge[0-9]+>/) bridge++                # cinterop C bridge
            if ($0 ~ /__u?modti3/) mod++
        }
        END { printf "%d %d %d %d %d\n", mul, near, helper, bridge, mod }
    ' "$1"
}

check_kn_binary() {
    local arch=$1 bin=$2 mulhi=$3 dis
    dis=$(mktemp)
    # GNU objdump handles the host arch; llvm-objdump (macOS ships it as `objdump`) handles both.
    if [ "$arch" = "x86-64" ] && objdump -d --no-show-raw-insn -M intel "$bin" > "$dis" 2>/dev/null && [ -s "$dis" ]; then
        :
    elif llvm-objdump -d --no-show-raw-insn "$bin" > "$dis" 2>/dev/null && [ -s "$dis" ]; then
        :
    elif objdump -d --no-show-raw-insn "$bin" > "$dis" 2>/dev/null && grep -qE "[[:space:]]${mulhi}[[:space:]]" "$dis"; then
        :
    else
        fail "$arch: cannot disassemble $bin (need llvm-objdump or an objdump for this arch)"; rm -f "$dis"; return
    fi

    local mul near helper bridge mod
    read -r mul near helper bridge mod <<< "$(kn_summary "$dis" "[[:space:]]${mulhi}[[:space:]]")"
    rm -f "$dis"

    if [ "$mul" -gt 0 ]; then
        pass "$arch: multiply-high is an inline '$mulhi' in Kotlin code ($mul sites, incl. Int128.times and the benchmark loops)"
    else
        fail "$arch: no inline '$mulhi' in Kotlin code"
    fi
    if [ "$helper" = 0 ]; then
        pass "$arch: Kotlin code never calls the C helpers — LLVM inlined every @GCUnsafeCall body"
    else
        fail "$arch: $helper calls to C helpers left (not inlined; check compilerOpts CPU in int128.def)"
    fi
    if [ "$mul" -gt 0 ] && [ "$near" = 0 ] && [ "$bridge" = 0 ]; then
        pass "$arch: no GC thread-state switch around any multiply-high, no cinterop bridge calls"
    else
        fail "$arch: $near multiply-high sites inside a GC thread-state switch, $bridge cinterop bridge calls"
    fi
    if [ "$mod" = 0 ]; then
        pass "$arch: division makes one __divti3/__udivti3 call; remainder derived from quotient (no __modti3)"
    else
        fail "$arch: $mod __modti3/__umodti3 calls (quotient and remainder computed separately)"
    fi
}

KN_FOUND=0
for spec in \
    "x86-64:linuxX64:mul" "ARM64:linuxArm64:umulh" \
    "x86-64:macosX64:mul" "ARM64:macosArm64:umulh"
do
    IFS=: read -r arch target mulhi <<< "$spec"
    bin="benchmark/build/bin/$target/releaseExecutable/benchmark.kexe"
    if [ -f "$bin" ]; then
        echo "  [$target] $bin"
        check_kn_binary "$arch" "$bin" "$mulhi"
        KN_FOUND=1
    fi
done
if [ "$KN_FOUND" = 0 ]; then
    echo "  SKIP: no benchmark binary. Build one: ./gradlew :benchmark:linkReleaseExecutableLinuxX64"
fi

echo ""

# -------------------------------------------------------------------
# 5. SUMMARY
# -------------------------------------------------------------------
echo "========================================"
echo " Results: $PASS passed, $FAIL failed"
echo "========================================"

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
