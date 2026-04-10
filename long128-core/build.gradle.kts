plugins {
    alias(libs.plugins.kotlin.multiplatform)
    `maven-publish`
    signing
}

group = "com.vitorpamplona.long128"
version = "0.1.0-SNAPSHOT"

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    macosArm64()
    macosX64()
    iosArm64()
    iosX64()
    iosSimulatorArm64()
    linuxX64()
    linuxArm64()
    mingwX64()

    targets.withType<org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget> {
        compilations.getByName("main") {
            cinterops {
                create("int128") {
                    defFile(project.file("src/nativeInterop/cinterop/int128.def"))
                }
            }
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

publishing {
    publications.withType<MavenPublication> {
        pom {
            name.set("Long128-kmp")
            description.set("Kotlin Multiplatform 128-bit integer types (Int128/UInt128) with platform-optimized arithmetic")
            url.set("https://github.com/vitorpamplona/Long128-kmp")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/licenses/MIT")
                }
            }
            developers {
                developer {
                    id.set("vitorpamplona")
                    name.set("Vitor Pamplona")
                    url.set("https://github.com/vitorpamplona")
                }
            }
            scm {
                url.set("https://github.com/vitorpamplona/Long128-kmp")
                connection.set("scm:git:git://github.com/vitorpamplona/Long128-kmp.git")
                developerConnection.set("scm:git:ssh://git@github.com/vitorpamplona/Long128-kmp.git")
            }
        }
    }

    repositories {
        maven {
            name = "sonatype"
            val releasesUrl = uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            val snapshotsUrl = uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            url = if (version.toString().endsWith("SNAPSHOT")) snapshotsUrl else releasesUrl
            credentials {
                username = findProperty("ossrhUsername") as String? ?: System.getenv("OSSRH_USERNAME") ?: ""
                password = findProperty("ossrhPassword") as String? ?: System.getenv("OSSRH_PASSWORD") ?: ""
            }
        }
    }
}

signing {
    val signingKey = findProperty("signingKey") as String? ?: System.getenv("SIGNING_KEY")
    val signingPassword = findProperty("signingPassword") as String? ?: System.getenv("SIGNING_PASSWORD")
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications)
    }
}
