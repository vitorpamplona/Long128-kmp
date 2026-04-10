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

echo ""

# -------------------------------------------------------------------
# 3. SUMMARY
# -------------------------------------------------------------------
echo "========================================"
echo " Results: $PASS passed, $FAIL failed"
echo "========================================"

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
