pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }

    versionCatalogs {
        // Второй каталог — только для сэмпла. Библиотечный `libs` описывает то, что
        // публикуется, и не должен содержать Compose Multiplatform, Ktor и прочие
        // зависимости приложения-примера. См. gradle/sample.versions.toml.
        create("samplelibs") {
            from(files("gradle/sample.versions.toml"))
        }
    }
}

rootProject.name = "kache"
include(":cache-core")
include(":cache-storage")
include(":cache-store-room")
include(":cache-android")
include(":sample")
include(":sample-android")
