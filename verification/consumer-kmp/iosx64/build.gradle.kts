plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvm()
    // `:cache-core` is the only module with an `iosX64` variant. This module exists to prove
    // that variant resolves from the repository for an Intel-simulator target.
    iosX64()

    sourceSets {
        commonMain.dependencies {
            implementation("io.github.slavikjunior.kache:cache-core:0.1.0")
        }
    }
}
