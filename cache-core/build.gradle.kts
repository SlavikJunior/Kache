plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.dokka)
    id("kache.publish")
}

kotlin {
    // Enforce explicit visibility modifiers for all public API
    explicitApi()

    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    android {
        namespace = "io.github.slavikjunior.kache.core"
        compileSdk = 36
        minSdk = 23

        // Without a host test builder the Android target contributes no unit test
        // compilation, so commonTest would only be verified on JVM and iOS.
        withHostTestBuilder {}.configure {
            isIncludeAndroidResources = false
        }
    }

    sourceSets {
        commonMain.dependencies {
            // Flow<CacheResult<V>> is part of KmpCache public API
            api(libs.kotlinx.coroutines.core)
            
            // Clock.System is used only internally by SystemTimeSource
            implementation(libs.kotlinx.datetime)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.datetime)
        }
    }
}

// The Android compilation is not covered by the `jvm { compilerOptions }` block above and
// would otherwise target whatever JDK the Gradle daemon runs on. A Robolectric host test
// in a dependent module runs on JDK 17 (see :cache-store-room), so the bytecode it links
// against has to stay loadable there. Task-level configuration is used because the
// extension-level compilerOptions does not reach the AGP KMP Android compilation.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
