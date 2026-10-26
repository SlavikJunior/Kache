import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Приложение-пример на Compose Multiplatform: Android, десктоп и iOS из одного
 * `commonMain`.
 *
 * Модуль — KMP-библиотека, а не application: AGP 9 запрещает `com.android.application`
 * в одном субпроекте с `org.jetbrains.kotlin.multiplatform`. Android-точка входа живёт
 * в отдельном `:sample-android`, десктопная и iOS-ная — в `desktopMain`/`iosMain`
 * этого модуля.
 *
 * Ничего из этого не публикуется и не участвует в `apiCheck`: сэмпл не является
 * частью контракта библиотеки.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    // Compose Multiplatform и его зависимости берутся из отдельного каталога
    // (gradle/sample.versions.toml): в библиотечном `libs` им не место.
    alias(samplelibs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        // Namespace отличается от applicationId приложения: два модуля с одним
        // namespace Gradle считает конфликтом.
        namespace = "io.github.slavikjunior.kache.sample.shared"
        compileSdk = 37
        minSdk = 23

        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }

        // Общие тесты гоняются и на Android-хосте: это ловит случай, когда
        // commonMain собрался под JVM, но не под Android.
        withHostTestBuilder {}
    }

    // Имя `desktop`, а не `jvm`: так source set называется `desktopMain/desktopTest`,
    // и это же имя ожидает DSL `compose.desktop`.
    jvm("desktop") {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    // iosX64 (симулятор на Intel) не объявляется: Apple сняла его с поддержки,
    // а :cache-store-room для него всё равно не резолвится.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Sample"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":cache-core"))
            implementation(project(":cache-storage"))

            // Аксессоры `compose.*` помечены в CMP 1.12 как устаревшие в пользу прямых
            // координат, но версии артефактов там расходятся (material3 1.9.0,
            // icons 1.7.3, остальное 1.12.2), и ручной пиннинг в каталоге разъедется с
            // плагином при обновлении. Оставляем DSL — он остаётся официальным способом
            // и уйдёт вместе с плагином.
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.materialIconsExtended)
            implementation(compose.components.uiToolingPreview)

            implementation(samplelibs.jetbrains.lifecycle.runtime.compose)
            implementation(samplelibs.jetbrains.lifecycle.viewmodel.compose)

            implementation(samplelibs.ktor.client.core)
            implementation(samplelibs.ktor.client.content.negotiation)
            implementation(samplelibs.ktor.serialization.kotlinx.json)

            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }

        androidMain.dependencies {
            // Android-специфичная обвязка кеша: фабрика на Context. На десктопе и iOS
            // движок создаётся напрямую, поэтому модуль подключается только здесь.
            implementation(project(":cache-android"))
            implementation(samplelibs.ktor.client.android)
        }

        iosMain.dependencies {
            // На iOS нет файлового движка (java.io), зато есть Room-движок, который
            // работает поверх bundled SQLite. Это даёт сэмплу настоящий L2 на всех
            // трёх платформах.
            implementation(project(":cache-store-room"))
            implementation(samplelibs.ktor.client.darwin)
        }

        getByName("desktopMain") {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(samplelibs.ktor.client.cio)

                // Dispatchers.Main для десктопного JVM. Версия берётся из библиотечного
                // каталога, чтобы диспетчер не разъехался с kotlinx-coroutines-core,
                // который использует общий код. Координаты указаны строкой намеренно:
                // этот артефакт нужен только сэмплу, и в каталоге библиотеки ему не место.
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:${libs.versions.coroutines.get()}")
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.github.slavikjunior.kache.sample.MainKt"
    }
}
