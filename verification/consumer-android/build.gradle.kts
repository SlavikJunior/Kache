plugins {
    // AGP 9 includes Kotlin support, so no Kotlin Android plugin is applied here — exactly
    // like a plain Android consumer would. The serialization plugin is still required for
    // `@Serializable`; the runtime comes transitively from `cache-storage`.
    id("com.android.library") version "9.4.1"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20"
}

android {
    namespace = "io.github.slavikjunior.kache.consumer.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Coordinates only, resolved from the local Maven repository. There is deliberately no
// `project(...)` anywhere: the whole point is to cross the boundary a real consumer crosses.
dependencies {
    implementation("io.github.slavikjunior.kache:cache-core:1.0.0")
    implementation("io.github.slavikjunior.kache:cache-storage:1.0.0")
    implementation("io.github.slavikjunior.kache:cache-android:1.0.0")
    implementation("io.github.slavikjunior.kache:cache-store-room:1.0.0")

    // No explicit kotlinx-serialization dependency: `KSerializer` is part of
    // KotlinxJsonSerializer's public API, so `cache-storage` has to expose it as `api`.
    // If that scope ever regresses to `implementation`, this module stops compiling.
}
