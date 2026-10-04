// Путь к локальному Maven-репозиторию задаётся свойством, чтобы проект не зависел от того,
// где лежит исходный репозиторий Kache: ../../build/repo относительно этого каталога.
pluginManagement {
    repositories {
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
        mavenCentral()
    }
}

rootProject.name = "kache-consumer"