#!/bin/bash
# run-benchmarks.sh — Compile and run C vs Kotlin benchmarks side by side.
#
# Measures how much overhead the Kotlin compilation stack adds vs pure C __int128.
# The C benchmark is the performance floor; Kotlin results show the cost of:
# - JVM: HotSpot JIT, object allocation, MethodHandle dispatch
# - Native: cinterop call overhead, Kotlin/Native LLVM codegen
#
# Usage: ./benchmark/run-benchmarks.sh

set -e
cd "$(dirname "$0")/.."

echo "=============================================="
echo " Long128-kmp: C vs Kotlin Benchmark"
echo " $(uname -m) / $(uname -s) / JDK $(java -version 2>&1 | head -1 | grep -oP '"\K[^"]+')"
echo "=============================================="
echo ""

# ── 1. Build and run C reference ──────────────────────────────────

echo "[1/2] Building and running C benchmark (gcc -O2)..."
echo ""
gcc -O2 -o /tmp/long128_bench_c benchmark/benchmark_c.c 2>/dev/null
C_OUTPUT=$(/tmp/long128_bench_c)
echo "$C_OUTPUT"
echo ""

# ── 2. Build and run Kotlin JVM ───────────────────────────────────

echo "[2/2] Building and running Kotlin JVM benchmark..."
echo ""
KT_OUTPUT=$(./gradlew -q :benchmark:jvmRun 2>/dev/null)
echo "$KT_OUTPUT"
echo ""

# ── 3. Side-by-side comparison ────────────────────────────────────

echo "=============================================="
echo " Side-by-side comparison"
echo "=============================================="
printf "  %-18s %10s %10s %10s\n" "Operation" "C (ns)" "Kotlin (ns)" "Ratio"
echo "  ──────────────────────────────────────────────"

declare -A c_results kt_results

while IFS= read -r line; do
    name=$(echo "$line" | sed 's/^ *//' | awk '{print $1}' | sed 's/://; s/^c_//')
    value=$(echo "$line" | grep -oP '[\d.]+(?= ns/op)')
    [ -n "$name" ] && [ -n "$value" ] && c_results[$name]=$value
done <<< "$C_OUTPUT"

while IFS= read -r line; do
    name=$(echo "$line" | sed 's/^ *//' | awk '{print $1}' | sed 's/://; s/^kt_//')
    value=$(echo "$line" | grep -oP '[\d.]+(?= ns/op)')
    [ -n "$name" ] && [ -n "$value" ] && kt_results[$name]=$value
done <<< "$KT_OUTPUT"

for op in add sub mul mul_high shift compare div_by_small div_by_large mixed; do
    c_val=${c_results[$op]:-"—"}
    kt_val=${kt_results[$op]:-"—"}
    if [ "$c_val" != "—" ] && [ "$kt_val" != "—" ]; then
        ratio=$(echo "scale=1; $kt_val / $c_val" | bc 2>/dev/null || echo "?")
        printf "  %-18s %8s    %8s      %sx\n" "$op" "$c_val" "$kt_val" "$ratio"
    else
        printf "  %-18s %8s    %8s      %s\n" "$op" "$c_val" "$kt_val" "—"
    fi
done

echo ""
echo "  Ratio = Kotlin/C  (1.0x = same speed, 2.0x = Kotlin is 2x slower)"
echo ""
echo "  What the ratio measures:"
echo "    add/sub/shift/compare: Kotlin carry-detection pattern vs C __int128"
echo "    mul/mul_high: MethodHandle→Math.multiplyHigh vs C mul instruction"
echo "    div: Pure-Kotlin O(128) loop vs C __divti3 runtime"
echo "    mixed: Realistic workload (mul-accumulate + occasional mod)"
echo ""
