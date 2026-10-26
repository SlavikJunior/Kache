plugins {
    // AGP 9 has built-in Kotlin support, so no separate Kotlin plugin is needed.
    alias(libs.plugins.android.application)
    // Compose-код здесь — только `setContent { }`; сам UI приходит из `:sample`.
    alias(libs.plugins.kotlin.compose)
}

/**
 * Тонкая Android-точка входа.
 *
 * UI, домен и данные живут в `:sample`. Этот модуль существует потому, что AGP 9
 * запрещает `com.android.application` в одном субпроекте с KMP-плагином: точка входа
 * обязана быть отдельным модулем. Здесь только `MainActivity`, `Application`,
 * манифест и тема — ничего, что стоило бы тестировать отдельно.
 */
android {
    namespace = "io.github.slavikjunior.kache.sample"
    // Compose Multiplatform 1.12 тянет Android-артефакты, собранные против API 36+;
    // Compose BOM больше не используется, поэтому версия платформы одна на весь проект.
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

dependencies {
    implementation(project(":sample"))

    // Нужен для `setContent` и `enableEdgeToEdge`. Compose BOM намеренно не
    // подключается: версии Compose задаёт Compose Multiplatform, и второй BOM дал бы
    // два набора одних и тех же классов в одном classpath.
    implementation(samplelibs.androidx.activity.compose)
}
