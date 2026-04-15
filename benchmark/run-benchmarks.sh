#!/bin/bash
# run-benchmarks.sh — Run C and Kotlin benchmarks, print side-by-side comparison.
#
# Usage: ./benchmark/run-benchmarks.sh
#
# Requires: gcc, java, gradle wrapper (./gradlew)

set -uo pipefail
cd "$(dirname "$0")/.."

JDK_VER=$(java -version 2>&1 | awk -F'"' '/version/ {print $2; exit}')
[ -z "$JDK_VER" ] && JDK_VER="unknown"

echo "══════════════════════════════════════════════════════════════════"
echo " Long128-kmp: C vs Kotlin JVM Benchmark"
echo " $(uname -m) / $(uname -s) / JDK $JDK_VER"
echo "══════════════════════════════════════════════════════════════════"
echo ""

# ── 1. C reference ───────────────────────────────────────────────────

echo "[1/2] C __int128 (gcc -O2) — performance floor"
echo ""
gcc -O2 -o /tmp/long128_bench_c benchmark/benchmark_c.c 2>/dev/null
C_RAW=$(/tmp/long128_bench_c)
echo "$C_RAW"
echo ""

# ── 2. Kotlin JVM ────────────────────────────────────────────────────

echo "[2/2] Kotlin Int128 (JVM, JDK $JDK_VER)"
echo ""
KT_RAW=$(./gradlew -q :benchmark:jvmRun 2>/dev/null | grep -v "^Picked up JAVA")
echo "$KT_RAW"
echo ""

# ── 3. Parse results ─────────────────────────────────────────────────

parse_ns() {
    # Extract "label: 123.45 ns/op" → variables named <prefix>_<label>
    # (bash 3.2 compatible: no associative arrays, no namerefs — macOS ships bash 3.2)
    local prefix=$1
    local data=$2
    local line label value safe_label
    while IFS= read -r line; do
        label=$(echo "$line" | sed 's/^ *//' | awk '{print $1}' | sed 's/://')
        # Extract the number that immediately precedes "ns/op" — portable awk replacement for grep -P lookahead
        value=$(echo "$line" | awk '{for(i=1;i<=NF;i++) if($i=="ns/op") {print $(i-1); exit}}')
        if [ -n "$label" ] && [ -n "$value" ]; then
            # printf (not echo) so tr -c doesn't pick up a trailing newline
            safe_label=$(printf '%s' "$label" | tr -c 'a-zA-Z0-9_' '_')
            eval "${prefix}_${safe_label}=\$value"
        fi
    done <<< "$data"
}

# Look up a parsed value: get_val <prefix> <key>, prints "—" if unset.
get_val() {
    local var="$1_$2"
    eval "printf '%s' \"\${$var:-—}\""
}

parse_ns C "$C_RAW"
# Parse all Kotlin output — labels are unique across tiers
parse_ns KT_SCALAR "$KT_RAW"
parse_ns KT_INT128 "$KT_RAW"
parse_ns KT_ARRAY "$KT_RAW"

# ── 4. Comparison tables ─────────────────────────────────────────────

ratio() {
    local c=$1 kt=$2
    if [ "$c" = "—" ] || [ "$kt" = "—" ] || [ -z "$c" ] || [ -z "$kt" ]; then
        echo "—"
        return
    fi
    # Check if C value is effectively zero
    local is_zero
    is_zero=$(echo "$c == 0" | bc 2>/dev/null || echo "1")
    if [ "$is_zero" = "1" ]; then
        echo "<0.3"
        return
    fi
    echo "scale=1; $kt / $c" | bc 2>/dev/null || echo "?"
}

echo "══════════════════════════════════════════════════════════════════"
echo " INSTRUCTION COST: C __int128 vs Kotlin scalar (raw Long pairs)"
echo " (No object allocation — what MFVC will give us)"
echo "══════════════════════════════════════════════════════════════════"
printf "  %-18s %10s %10s %8s\n" "Operation" "C (ns)" "Scalar (ns)" "Ratio"
echo "  ────────────────────────────────────────────────────────────"

for pair in \
    "add:c_add:scalar_add" \
    "sub:c_sub:scalar_sub" \
    "multiply:c_mul:scalar_mul" \
    "mul high:c_mul_high:scalar_mul_high" \
    "shift:c_shift:scalar_shift" \
    "compare:c_compare:scalar_compare"
do
    IFS=: read -r label c_key kt_key <<< "$pair"
    c_val=$(get_val C "$c_key")
    kt_val=$(get_val KT_SCALAR "$kt_key")
    r=$(ratio "$c_val" "$kt_val")
    printf "  %-18s %8s    %8s      %sx\n" "$label" "$c_val" "$kt_val" "$r"
done

echo ""
echo "══════════════════════════════════════════════════════════════════"
echo " ALLOCATION COST: Kotlin scalar vs Int128 objects"
echo " (Same instructions, but each result allocates a heap object)"
echo "══════════════════════════════════════════════════════════════════"
printf "  %-18s %10s %10s %8s\n" "Operation" "Scalar (ns)" "Int128 (ns)" "Overhead"
echo "  ────────────────────────────────────────────────────────────"

for pair in \
    "add:scalar_add:int128_add" \
    "sub:scalar_sub:int128_sub" \
    "multiply:scalar_mul:int128_mul" \
    "shift:scalar_shift:int128_shift" \
    "compare:scalar_compare:int128_compare"
do
    IFS=: read -r label s_key i_key <<< "$pair"
    s_val=$(get_val KT_SCALAR "$s_key")
    i_val=$(get_val KT_INT128 "$i_key")
    r=$(ratio "$s_val" "$i_val")
    printf "  %-18s %8s    %8s      %sx\n" "$label" "$s_val" "$i_val" "$r"
done

echo ""
echo "══════════════════════════════════════════════════════════════════"
echo " FULL COMPARISON: C vs Int128 vs Int128Array"
echo "══════════════════════════════════════════════════════════════════"
printf "  %-14s %8s %8s %8s %8s %8s\n" "Operation" "C" "Scalar" "Int128" "Array" "C→Int128"
echo "  ──────────────────────────────────────────────────────────────"

for pair in \
    "add:c_add:scalar_add:int128_add:array_add" \
    "multiply:c_mul:scalar_mul:int128_mul:array_mul"
do
    IFS=: read -r label c_key s_key i_key a_key <<< "$pair"
    c_val=$(get_val C "$c_key")
    s_val=$(get_val KT_SCALAR "$s_key")
    i_val=$(get_val KT_INT128 "$i_key")
    a_val=$(get_val KT_ARRAY "$a_key")
    r=$(ratio "$c_val" "$i_val")
    printf "  %-14s %6s   %6s   %6s   %6s   %sx\n" "$label" "$c_val" "$s_val" "$i_val" "$a_val" "$r"
done

# Division (no scalar/array variants)
for pair in \
    "div (small):c_div_by_small:int128_div_sm" \
    "div (large):c_div_by_large:int128_div_lg"
do
    IFS=: read -r label c_key i_key <<< "$pair"
    c_val=$(get_val C "$c_key")
    i_val=$(get_val KT_INT128 "$i_key")
    r=$(ratio "$c_val" "$i_val")
    printf "  %-14s %6s   %6s   %6s   %6s   %sx\n" "$label" "$c_val" "—" "$i_val" "—" "$r"
done

# Mixed
c_val=$(get_val C c_mixed)
i_val=$(get_val KT_INT128 int128_mixed)
r=$(ratio "$c_val" "$i_val")
printf "  %-14s %6s   %6s   %6s   %6s   %sx\n" "mixed" "$c_val" "—" "$i_val" "—" "$r"

echo ""
echo "  Ratio = Kotlin ns / C ns  (1.0x = same speed)"
echo ""
echo "  Columns:"
echo "    C        — gcc -O2, __int128, register arithmetic (performance floor)"
echo "    Scalar   — raw Long pairs in Kotlin, no Int128 objects (MFVC preview)"
echo "    Int128   — real library API, heap object per result"
echo "    Array    — Int128Array flat backing, pre-allocated storage"
echo ""
