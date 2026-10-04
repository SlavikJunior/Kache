plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    application
}

dependencies {
    // Намеренно без `project(":cache-core")`: единственная ценность этого проекта в том,
    // что он разрешает библиотеку как внешнюю зависимость из Maven-репозитория. Если
    // подключить исходники напрямую, сломается ровно то, что он должен проверять —
    // координаты, POM, sources и javadoc-артефакты.
    implementation("io.github.slavikjunior.kache:cache-core:0.1.0")
    implementation("io.github.slavikjunior.kache:cache-storage:0.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}

application { mainClass.set("MainKt") }

kotlin { jvmToolchain(17) }