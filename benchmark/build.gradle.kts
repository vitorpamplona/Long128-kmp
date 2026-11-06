import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        mainRun {
            mainClass.set("com.vitorpamplona.long128.benchmark.BenchmarkJvmKt")
        }
    }

    // Native benchmark executables. run-benchmarks.sh runs the one matching the host:
    //   ./gradlew -q :benchmark:runReleaseExecutableLinuxX64
    linuxX64()
    linuxArm64()
    macosArm64()
    macosX64()

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        binaries.executable {
            entryPoint = "com.vitorpamplona.long128.benchmark.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":long128-core"))
        }
    }
}
