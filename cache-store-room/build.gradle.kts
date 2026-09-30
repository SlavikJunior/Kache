plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
    alias(libs.plugins.dokka)
    id("kache.publish")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    api(project(":cache-core"))
    // Room/SQLite types appear in the public API (KacheDatabase, SQLiteDriver),
    // so they must be exposed to consumers.
    api(libs.androidx.room.runtime)
    api(libs.androidx.sqlite)
    // Bundled native SQLite: no external JDBC driver or Android framework required
    implementation(libs.androidx.sqlite.bundled)
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

dependencies {
    add("ksp", libs.androidx.room.compiler)
}

ksp {
    arg("room.generateKotlin", "true")
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("ksp.incremental", "true")
}

// KSP не нужен для тестов: Room-код генерируется только в main.
afterEvaluate {
    tasks.findByName("kspTestKotlin")?.enabled = false
}

// Enforce explicit visibility modifiers for the public API (main only).
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile> {
    val isTest = name.contains("Test") || name.contains("test")
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        if (!isTest) {
            freeCompilerArgs.add("-Xexplicit-api=strict")
        }
    }
}

// KMP-плагин создаёт публикации автоматически, для JVM-модуля нужно явно.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
