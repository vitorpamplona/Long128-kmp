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
            mainClass.set("com.vitorpamplona.long128.examples.ExamplesMainKt")
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":long128-core"))
        }
    }
}
