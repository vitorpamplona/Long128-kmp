plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    sourceSets {
        jvmMain.dependencies {
            implementation(project(":long128-core"))
        }
    }
}

tasks.register<JavaExec>("jvmRun") {
    dependsOn("jvmJar")
    val jvmJar = tasks.named<Jar>("jvmJar")
    classpath = files(jvmJar.map { it.archiveFile }) +
        configurations.getByName("jvmRuntimeClasspath")
    mainClass.set("com.vitorpamplona.long128.benchmark.BenchmarkJvmKt")
}
