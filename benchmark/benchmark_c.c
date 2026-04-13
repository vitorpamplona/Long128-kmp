/**
 * C reference benchmark for 128-bit arithmetic.
 *
 * This is the performance floor — pure __int128 with no language overhead.
 * Every Kotlin measurement is compared against this baseline to quantify
 * the cost of the compilation stack (HotSpot JIT, Kotlin/Native LLVM,
 * cinterop overhead, object allocation, etc.).
 *
 * Compile: gcc -O2 -o benchmark_c benchmark_c.c
 * Run:     ./benchmark_c
 *
 * Each benchmark runs N iterations in a tight loop and reports ns/op.
 * A volatile sink prevents the compiler from optimizing away the computation.
 */

#include <stdio.h>
#include <stdint.h>
#include <time.h>

typedef unsigned __int128 u128;
typedef __int128 s128;

/* Volatile sink — prevents dead code elimination */
static volatile int64_t sink;

static inline u128 make_u128(int64_t hi, int64_t lo) {
    return ((u128)(uint64_t)hi << 64) | (uint64_t)lo;
}

static inline double elapsed_ns(struct timespec start, struct timespec end) {
    return (end.tv_sec - start.tv_sec) * 1e9 + (end.tv_nsec - start.tv_nsec);
}

/* ── Benchmarks ───────────────────────────────────────────────────── */

static void bench_add(int iterations) {
    u128 a = make_u128(0x12345678, 0x9ABCDEF0);
    u128 b = make_u128(0xFEDCBA98, 0x76543210);
    u128 acc = 0;

    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);

    for (int i = 0; i < iterations; i++) {
        acc += a + (u128)i; /* data dependency on i prevents vectorization */
        acc += b;
    }

    clock_gettime(CLOCK_MONOTONIC, &end);
    sink = (int64_t)acc; /* prevent DCE */

    double ns = elapsed_ns(start, end) / (iterations * 2);
    printf("  c_add:           %8.2f ns/op\n", ns);
}

static void bench_sub(int iterations) {
    u128 a = make_u128(0xFFFFFFFF, 0xFFFFFFFF);
    u128 b = make_u128(0x00000001, 0x00000001);
    u128 acc = make_u128(0x7FFFFFFF, 0xFFFFFFFF);

    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);

    for (int i = 0; i < iterations; i++) {
        acc -= b + (u128)i; /* data dependency prevents vectorization */
        acc += a;
    }

    clock_gettime(CLOCK_MONOTONIC, &end);
    sink = (int64_t)acc;

    double ns = elapsed_ns(start, end) / (iterations * 2);
    printf("  c_sub:           %8.2f ns/op\n", ns);
}

static void bench_mul(int iterations) {
    u128 a = make_u128(0x12345678, 0x9ABCDEF0);
    u128 b = make_u128(0x00000000, 0xFEDCBA9876543210ULL);
    u128 acc = 1;

    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);

    for (int i = 0; i < iterations; i++) {
        acc = acc * a + b;
    }

    clock_gettime(CLOCK_MONOTONIC, &end);
    sink = (int64_t)acc;

    double ns = elapsed_ns(start, end) / iterations;
    printf("  c_mul:           %8.2f ns/op\n", ns);
}

static void bench_mul_high(int iterations) {
    int64_t a = 0x123456789ABCDEF0LL;
    int64_t b = 0xFEDCBA9876543210LL;
    int64_t acc = 0;

    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);

    for (int i = 0; i < iterations; i++) {
        u128 product = (u128)(uint64_t)a * (uint64_t)b;
        acc += (int64_t)(product >> 64);
        a += 1;
    }

    clock_gettime(CLOCK_MONOTONIC, &end);
    sink = acc;

    double ns = elapsed_ns(start, end) / iterations;
    printf("  c_mul_high:      %8.2f ns/op\n", ns);
}

static void bench_div(int iterations) {
    u128 a = make_u128(0x12345678, 0x9ABCDEF0);
    u128 b = make_u128(0x00000000, 0x0000000000000007ULL);
    u128 acc = a;

    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);

    for (int i = 0; i < iterations; i++) {
        acc = acc / b + a;
    }

    clock_gettime(CLOCK_MONOTONIC, &end);
    sink = (int64_t)acc;

    double ns = elapsed_ns(start, end) / iterations;
    printf("  c_div_by_small:  %8.2f ns/op\n", ns);
}

static void bench_div_large(int iterations) {
    u128 a = make_u128(0xFFFFFFFFFFFFFFFFULL, 0xFFFFFFFFFFFFFFF0ULL);
    u128 b = make_u128(0x0000000000000003ULL, 0x0000000000000007ULL);
    u128 acc = a;

    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);

    for (int i = 0; i < iterations; i++) {
        acc = acc / b + a;
    }

    clock_gettime(CLOCK_MONOTONIC, &end);
    sink = (int64_t)acc;

    double ns = elapsed_ns(start, end) / iterations;
    printf("  c_div_by_large:  %8.2f ns/op\n", ns);
}

static void bench_shift(int iterations) {
    u128 a = make_u128(0x12345678, 0x9ABCDEF0);
    u128 acc = a;

    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);

    for (int i = 0; i < iterations; i++) {
        acc = (acc << 7) ^ (acc >> 3);
    }

    clock_gettime(CLOCK_MONOTONIC, &end);
    sink = (int64_t)acc;

    double ns = elapsed_ns(start, end) / iterations;
    printf("  c_shift:         %8.2f ns/op\n", ns);
}

static void bench_compare(int iterations) {
    s128 a = (s128)make_u128(0x12345678, 0x9ABCDEF0);
    s128 b = (s128)make_u128(0x12345678, 0x9ABCDEF1);
    int acc = 0;

    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);

    for (int i = 0; i < iterations; i++) {
        acc += (a < b) ? 1 : -1;
        a += 1;
    }

    clock_gettime(CLOCK_MONOTONIC, &end);
    sink = acc;

    double ns = elapsed_ns(start, end) / iterations;
    printf("  c_compare:       %8.2f ns/op\n", ns);
}

static void bench_mixed(int iterations) {
    /* Realistic workload: multiply-accumulate with occasional divide */
    u128 acc = make_u128(0, 1);
    u128 factor = make_u128(0, 0xFEDCBA9876543210ULL);
    u128 addend = make_u128(0x11, 0x22);
    u128 divisor = make_u128(0, 1000000007ULL);

    struct timespec start, end;
    clock_gettime(CLOCK_MONOTONIC, &start);

    for (int i = 0; i < iterations; i++) {
        acc = acc * factor + addend;
        if ((i & 0xFF) == 0) {
            acc = acc % divisor;
        }
    }

    clock_gettime(CLOCK_MONOTONIC, &end);
    sink = (int64_t)acc;

    double ns = elapsed_ns(start, end) / iterations;
    printf("  c_mixed:         %8.2f ns/op\n", ns);
}

/* ── Main ─────────────────────────────────────────────────────────── */

int main() {
    int N = 10000000; /* 10M iterations for stable timing */
    int N_DIV = 1000000; /* 1M for division (slower) */

    printf("C __int128 reference benchmark (%d iterations)\n", N);
    printf("─────────────────────────────────────────────\n");

    bench_add(N);
    bench_sub(N);
    bench_mul(N);
    bench_mul_high(N);
    bench_shift(N);
    bench_compare(N);
    bench_div(N_DIV);
    bench_div_large(N_DIV);
    bench_mixed(N);

    printf("─────────────────────────────────────────────\n");
    return 0;
}
