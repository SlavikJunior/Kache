import org.gradle.api.tasks.testing.Test
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

        // Robolectric needs merged resources to build its sandbox. Without a host test
        // builder the Android target would contribute no unit test compilation at all.
        withHostTestBuilder {}.configure {
            isIncludeAndroidResources = true
        }
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

        val androidHostTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.junit4)
                implementation(libs.robolectric)
            }
        }
    }
}

// Robolectric reaches into `java.io.FileDescriptor` by reflection and stops working on
// JDK 21 and newer, which is what the daemon runs here. The host test launcher is pinned
// to 17; src/androidHostTest/resources/robolectric.properties pins SDK 34 to match,
// because SDK 36 refuses to run below Java 21.
tasks.withType<Test>().configureEach {
    javaLauncher.set(
        javaToolchains.launcherFor {
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    )
}

// The Android compilation is a JVM one. The daemon JVM criteria would otherwise pick a
// Java 25 toolchain, and the published AAR is compiled against it.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The Android target publishes an AAR, and BCV cannot dump it (see AndroidApiSurfaceTest), so
// the test renders the public surface itself. It has to be told where the production classes
// are: host-test classes share the package and would otherwise be rendered as part of the API.
// `matching` rather than `named`: the AGP KMP plugin registers the host-test task lazily, so a
// direct lookup at configuration time fails.
tasks.matching { it.name == "testAndroidHostTest" }.configureEach {
    this as Test
    val classesDir = layout.buildDirectory.dir("classes/kotlin/android/main")
    systemProperty("kache.apiSurfaceClassesDir", classesDir.get().asFile.absolutePath)

    // Regenerating the golden file is an explicit act: `KACHE_UPDATE_API_SURFACE=1` is read by
    // the test, and Gradle has to pass the environment through for that to work.
    environment("KACHE_UPDATE_API_SURFACE", System.getenv("KACHE_UPDATE_API_SURFACE") ?: "")
}
