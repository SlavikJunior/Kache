plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvm()
    android {
        namespace = "io.github.slavikjunior.kache.consumer.kmp"
        compileSdk = 36
        minSdk = 23
    }

    // The targets the published artifacts declare. `iosX64` lives in `:iosx64` instead,
    // because `:cache-store-room` has no such variant.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        // `cache-store-room` is the only backend with an iOS target, so it is the only one
        // that can sit in `commonMain`. `cache-storage` is jvm/android-only and is therefore
        // added to those source sets alone — the rule a consumer has to follow.
        commonMain.dependencies {
            implementation("io.github.slavikjunior.kache:cache-core:0.1.0")
            implementation("io.github.slavikjunior.kache:cache-store-room:0.1.0")
        }
        jvmMain.dependencies {
            implementation("io.github.slavikjunior.kache:cache-storage:0.1.0")
        }
        androidMain.dependencies {
            implementation("io.github.slavikjunior.kache:cache-storage:0.1.0")
        }
    }
}
