plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilations.all {
            kotlinOptions.jvmTarget = "11"
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
