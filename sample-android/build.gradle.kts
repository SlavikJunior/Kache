plugins {
    // AGP 9 has built-in Kotlin support, so no separate Kotlin plugin is needed.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.github.slavikjunior.kache.sample"
    // Compose BOM 2026.09.x pulls in libraries that require compileSdk 37.
    //
    // Android 37 is minor-versioned and published as `platforms;android-37.0` and
    // `platforms;android-37.1`, so a bare `compileSdk = 37` resolves to whichever minor
    // happens to be installed. That makes a developer's machine and CI build against
    // different platforms. Pinning the minor keeps the two identical, and CI installs
    // exactly this one.
    compileSdk = 37
    compileSdkMinor = 0

    defaultConfig {
        applicationId = "io.github.slavikjunior.kache.sample"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// The sample is a plain Android app that consumes the KMP modules through Gradle
// project dependencies. It intentionally does not depend on `:cache-store-room`:
// that module ships bundled SQLite for the JVM and is not usable on Android.
// `:cache-android` supplies the Android conveniences (Context-based construction,
// memory-pressure callbacks) but stays Room-free, matching this module's dependencies.
dependencies {
    implementation(project(":cache-core"))
    implementation(project(":cache-storage"))
    implementation(project(":cache-android"))

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.compose.ui.tooling)
}