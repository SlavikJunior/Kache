plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
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
        namespace = "com.github.slavikjunior.kache.core"
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
