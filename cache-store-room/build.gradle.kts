import org.gradle.api.tasks.testing.Test
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
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
        namespace = "io.github.slavikjunior.kache.store.room"
        compileSdk = 36
        minSdk = 23

        withHostTestBuilder {}.configure {
            // Robolectric needs merged resources to build its Android sandbox, which is
            // what lets the Context-based `createFromContext` path be tested on the host.
            isIncludeAndroidResources = true
        }
    }

    iosArm64()
    iosSimulatorArm64()
    // No iosX64: androidx.sqlite 2.7.1 does not publish an iosX64 variant, and the
    // dependency cannot be resolved for that target. Intel-simulator support therefore
    // stays JVM-less for L2 while real devices (iosArm64) and Apple Silicon simulators
    // (iosSimulatorArm64) are covered. :cache-core still supports iosX64.

    sourceSets {
        commonMain.dependencies {
            api(project(":cache-core"))

            // Room/SQLite types appear in the public API (KacheDatabase, SQLiteDriver),
            // so they must be exposed to consumers.
            api(libs.androidx.room.runtime)
            api(libs.androidx.sqlite)
            // Bundled native SQLite: no external JDBC driver or Android framework required
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }

        val androidHostTest by getting {
            dependencies {
                implementation(libs.junit4)
                implementation(libs.robolectric)
                // BundledSQLiteDriver ships a JNI library built for Android ABIs, so it
                // cannot load in a host JVM test. The framework driver goes through
                // android.database.sqlite, which Robolectric shadows with a working
                // SQLite implementation.
                implementation(libs.androidx.sqlite.framework)
            }
        }
    }
}

// Robolectric allocates shared memory through `java.io.FileDescriptor` internals that it
// reaches by reflection, and that stops working on JDK 21 and newer. The launcher is
// pinned to 17 so the test process runs somewhere Robolectric can instrument, while the
// daemon stays free to use a modern JDK for compilation.
//
// The SDK has to move with it: src/androidHostTest/resources/robolectric.properties pins
// SDK 34, because SDK 36 refuses to run on anything older than Java 21. The two settings
// are only valid together.
//
// This matters on CI too: setup-java provides 17 there while
// gradle/gradle-daemon-jvm.properties pins the daemon to a recent JDK, so the launcher has
// to be set explicitly rather than inherited.
tasks.withType<Test>().configureEach {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    )
}

// Room code generation is configured per target rather than through the catch-all
// `ksp(...)` configuration, which KSP 2 deprecates. Every target listed here compiles the
// generated database implementation, so the engine behaves identically everywhere.
dependencies {
    listOf("kspJvm", "kspAndroid", "kspIosArm64", "kspIosSimulatorArm64").forEach { configuration ->
        add(configuration, libs.androidx.room.compiler)
    }
}

// Room must not be processed for test source sets: the schema would be emitted for test
// fixtures and the generated code is only needed by the main compilation. Test KSP tasks
// are named `kspTestKotlin<Target>` for the KMP targets and `kspAndroidHostTest` for the
// Android host-test compilation.
//
// The `ksp` prefix is essential: matching only on "HostTest" also matches
// `compileAndroidHostTest`, which silently disables the whole Android host test run.
afterEvaluate {
    tasks.matching {
        it.name.startsWith("ksp") && (it.name.startsWith("kspTest") || it.name.contains("HostTest"))
    }.configureEach {
        enabled = false
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>().configureEach {
    if (name.contains("Test", ignoreCase = true)) return@configureEach
    compilerOptions {
        freeCompilerArgs.add("-Xexplicit-api=strict")
    }
}

// Robolectric instruments test classes with an ASM version that cannot read Java 25
// bytecode, which is what the daemon JVM criteria would otherwise produce. Only the JVM
// and Android compilations are affected; the Native ones have no jvmTarget.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
