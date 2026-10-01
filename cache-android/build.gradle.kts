plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.dokka)
    id("kache.publish")
}

kotlin {
    // Enforce explicit visibility modifiers for all public API
    explicitApi()

    android {
        namespace = "io.github.slavikjunior.kache.android"
        compileSdk = 36
        minSdk = 23
    }

    sourceSets {
        androidMain.dependencies {
            // A thin Android convenience layer over the existing engines: it adds no
            // storage backend, so neither Room nor any UI toolkit is pulled in.
            api(project(":cache-core"))
            api(project(":cache-storage"))

            // Required by KacheableViewModel. Exposed as `api` because the base class
            // appears in the module's public signatures, so a consumer writing their own
            // subclass needs AndroidViewModel on the compile classpath.
            api(libs.androidx.lifecycle.viewmodel)

            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

// The Android compilation is a JVM one. The daemon JVM criteria would otherwise pick a
// Java 25 toolchain, and the published AAR is compiled against it.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}