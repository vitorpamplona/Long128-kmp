// JMH benchmarks for the JVM (via kotlinx-benchmark), mirroring benchmark/benchmark_c.c loop for loop.
//   ./gradlew :benchmark-jmh:jvmBenchmark            full run (forks, warmup, measurement)
//   ./gradlew :benchmark-jmh:jvmQuickBenchmark       shorter run for iterating
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.allopen)
    alias(libs.plugins.kotlinx.benchmark)
}

allOpen {
    annotation("org.openjdk.jmh.annotations.State")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":long128-core"))
            implementation(libs.kotlinx.benchmark.runtime)
        }
    }
}

benchmark {
    targets {
        register("jvm")
    }
    configurations {
        named("main") {
            warmups = 5
            iterations = 5
            iterationTime = 1
            iterationTimeUnit = "s"
            outputTimeUnit = "ns"
            mode = "avgt"
            advanced("jvmForks", "2")
            reportFormat = "json"
        }
        register("quick") {
            warmups = 3
            iterations = 3
            iterationTime = 500
            iterationTimeUnit = "ms"
            outputTimeUnit = "ns"
            mode = "avgt"
            advanced("jvmForks", "1")
            reportFormat = "json"
            include(project.findProperty("jmhInclude")?.toString() ?: ".*")
        }
    }
}
