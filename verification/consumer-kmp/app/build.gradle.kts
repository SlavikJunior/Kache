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
        // Both artifacts are on `commonMain` because both publish a variant for every
        // target here. `cache-storage` used to be jvm/android-only; its iOS klib carries the
        // serializers (`KotlinxJsonSerializer`, `StringSerializer`) while `FileStorageEngine`
        // stays on the JVM side, where `java.io` exists. A consumer that puts the dependency
        // in `commonMain` and only calls the serializers from common code is the boundary
        // this harness exists to prove.
        commonMain.dependencies {
            implementation("io.github.slavikjunior.kache:cache-core:0.1.0")
            implementation("io.github.slavikjunior.kache:cache-storage:0.1.0")
            implementation("io.github.slavikjunior.kache:cache-store-room:0.1.0")
        }
    }
}
