// Плагины объявляются один раз в корне с `apply false`, чтобы Gradle грузил их в общий
// classloader. Без этого `:app` (KMP + AGP) и `:iosx64` (только KMP) получают разные
// копии Kotlin-плагина, и сборка падает на приведении
// SwiftPMLockTaskAggregationBuildService.
plugins {
    id("org.jetbrains.kotlin.multiplatform") apply false
    id("org.jetbrains.kotlin.plugin.serialization") apply false
    id("com.android.kotlin.multiplatform.library") apply false
}
