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
    implementation("io.github.slavikjunior.kache:cache-core:1.0.0")
    implementation("io.github.slavikjunior.kache:cache-storage:1.0.0")

    // kotlinx-serialization намеренно не объявляется: `KSerializer` стоит в публичном
    // конструкторе `KotlinxJsonSerializer`, поэтому `:cache-storage` обязан отдавать его
    // как `api`. Если скоуп откатится к `implementation`, этот модуль перестанет
    // компилироваться — что и есть проверка.
}

application { mainClass.set("MainKt") }

kotlin { jvmToolchain(17) }