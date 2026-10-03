plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
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

    android {
        namespace = "io.github.slavikjunior.kache.storage"
        compileSdk = 36
        minSdk = 23

        withHostTestBuilder {}.configure {
            isIncludeAndroidResources = false
        }
    }

    sourceSets {
        // JVM and Android both use java.io, so FileStorageEngine is implemented once
        // in a source set shared by the two targets instead of being duplicated.
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain.get().dependsOn(jvmCommonMain)
        androidMain.get().dependsOn(jvmCommonMain)

        commonMain.dependencies {
            // FileStorageEngine implements StorageEngine, KacheSerializer is public
            api(project(":cache-core"))
            
            // Coroutines inherited transitively from :cache-core, but prefer explicit declaration
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
            implementation(project(":cache-core"))
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
