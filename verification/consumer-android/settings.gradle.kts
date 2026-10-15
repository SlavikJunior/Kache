// Отдельная сборка: путь к локальному Maven-репозиторию задаётся свойством, чтобы проект
// не зависел от того, где лежит исходный репозиторий Kache.
pluginManagement {
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

rootProject.name = "kache-consumer-android"
