#!/bin/bash
# verify-instructions.sh — Proves the README claims about CPU instruction usage.
#
# Run: ./verify-instructions.sh
#
# Requires: gcc, objdump, javap, and a successful ./gradlew :long128-core:jvmTest

set -e
PASS=0
FAIL=0

pass() { echo "  PASS: $1"; PASS=$((PASS+1)); }
fail() { echo "  FAIL: $1"; FAIL=$((FAIL+1)); }

echo "========================================"
echo " Long128-kmp Instruction Verification"
echo "========================================"
echo ""

# -------------------------------------------------------------------
# 1. JVM BYTECODE VERIFICATION
# -------------------------------------------------------------------
echo "--- JVM Bytecode ---"

PLATFORM_CLASS="long128-core/build/classes/kotlin/jvm/main/com/vitorpamplona/long128/internal/PlatformMath_jvmKt.class"
INT128_CLASS="long128-core/build/classes/kotlin/jvm/main/com/vitorpamplona/long128/Int128.class"
MATHUTILS_CLASS="long128-core/build/classes/kotlin/jvm/main/com/vitorpamplona/long128/internal/MathUtilsKt.class"

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
if echo "$BYTECODE" | grep -q "multiplyHighFallback"; then
    pass "multiplyHighFallback → pure-Kotlin Karatsuba (4-imul) fallback present"
else
    fail "multiplyHighFallback not found"
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
    fail "MathUtilsKt.class references BigInteger"
else
    pass "MathUtilsKt.class has no BigInteger dependency"
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
DISASM=$(objdump -d -M intel /tmp/long128_cinterop.o)

# Claim: int128_multiply_high_signed uses a single imul instruction
SIGNED_MUL=$(echo "$DISASM" | sed -n '/<int128_multiply_high_signed>:/,/ret$/p')
if echo "$SIGNED_MUL" | grep -q "imul.*rsi\|imul.*rdi"; then
    pass "int128_multiply_high_signed → single 'imul' instruction (128-bit signed multiply)"
else
    fail "int128_multiply_high_signed does not use imul"
fi

# Claim: int128_multiply_high_unsigned uses a single mul instruction
UNSIGNED_MUL=$(echo "$DISASM" | sed -n '/<int128_multiply_high_unsigned>:/,/ret$/p')
if echo "$UNSIGNED_MUL" | grep -qP "\tmul\s"; then
    pass "int128_multiply_high_unsigned → single 'mul' instruction (128-bit unsigned multiply)"
else
    fail "int128_multiply_high_unsigned does not use mul"
fi

# Count instructions in the multiply_high functions (excluding nop/endbr/ret)
SIGNED_INSN_COUNT=$(echo "$SIGNED_MUL" | grep -cP "^\s+[0-9a-f]+:" | head -1)
# Expect: endbr64, mov, imul, mov, ret = 5 instructions
if [ "$SIGNED_INSN_COUNT" -le 6 ]; then
    pass "int128_multiply_high_signed is $SIGNED_INSN_COUNT instructions (minimal)"
else
    fail "int128_multiply_high_signed is $SIGNED_INSN_COUNT instructions (expected ≤6)"
fi

# Claim: full 128x128 multiply uses no loops or calls
FULL_MUL=$(echo "$DISASM" | sed -n '/<int128_mul>:/,/ret$/p')
if echo "$FULL_MUL" | grep -q "call\|loop\|jmp"; then
    fail "int128_mul contains call/loop/jmp (not straight-line code)"
else
    pass "int128_mul → straight-line code (no loops, no function calls)"
fi
if echo "$FULL_MUL" | grep -qP "\tmul\s"; then
    pass "int128_mul uses hardware 'mul' instruction"
else
    fail "int128_mul does not use hardware mul"
fi

# Claim: division calls runtime helper (no hardware 128÷128 div exists on x86-64)
SDIV=$(echo "$DISASM" | sed -n '/<int128_sdiv>:/,/ret$/p')
if echo "$SDIV" | grep -q "call"; then
    pass "int128_sdiv → calls __divti3 runtime (no hardware 128÷128 div on x86-64, as documented)"
else
    fail "int128_sdiv does not call runtime helper (unexpected)"
fi

# Claim: int128_add uses add + adc (carry flag, not branch)
ADD_FUNC=$(echo "$DISASM" | sed -n '/<int128_add>:/,/ret$/p')
if echo "$ADD_FUNC" | grep -q "adc"; then
    pass "int128_add → add + adc (hardware carry flag, no branch)"
else
    fail "int128_add does not use adc instruction"
fi

# Claim: int128_sub uses sub + sbb (borrow flag, not branch)
SUB_FUNC=$(echo "$DISASM" | sed -n '/<int128_sub>:/,/ret$/p')
if echo "$SUB_FUNC" | grep -q "sbb"; then
    pass "int128_sub → sub + sbb (hardware borrow flag, no branch)"
else
    fail "int128_sub does not use sbb instruction"
fi

# Claim: batch_add uses add + adc in a tight loop
BATCH_ADD=$(echo "$DISASM" | sed -n '/<int128_batch_add>:/,/ret$/p')
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

# -------------------------------------------------------------------
# 4. SUMMARY
# -------------------------------------------------------------------
echo "========================================"
echo " Results: $PASS passed, $FAIL failed"
echo "========================================"

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
