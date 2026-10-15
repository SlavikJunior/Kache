// Отдельная сборка: путь к локальному Maven-репозиторию задаётся свойством, чтобы проект
// не зависел от того, где лежит исходный репозиторий Kache.
pluginManagement {
    // Плагины объявляются с версиями здесь, а не в каждом подпроекте: если один и тот же
    // id приходит с версией из разных модулей, Gradle грузит Kotlin-плагин в отдельные
    // classloader'ы и падает на SwiftPMLockTaskAggregationBuildService.
    plugins {
        id("org.jetbrains.kotlin.multiplatform") version "2.4.20"
        id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20"
        id("com.android.kotlin.multiplatform.library") version "9.4.1"
    }
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

val localRepo: String =
    providers.gradleProperty("kache.localRepo").orNull
        ?: rootDir.resolve("../../build/repo").absolutePath

dependencyResolutionManagement {
    repositories {
        maven { url = uri(localRepo) }
        google()
        mavenCentral()
    }
}

rootProject.name = "kache-consumer-kmp"

// `:app` covers the documented multiplatform targets (jvm, android, iosArm64,
// iosSimulatorArm64). `:iosx64` is separate because `:cache-store-room` publishes no
// `iosX64` variant, so a target set that includes it cannot take the other module as a
// `commonMain` dependency.
include(":app")
include(":iosx64")
