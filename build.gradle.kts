plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.bcv)
}

tasks.dokkaHtmlMultiModule {
    outputDirectory.set(layout.buildDirectory.dir("dokka"))
}

apiValidation {
    // Exclude modules without a stable public API or test-only modules
    ignoredPackages.add("com.github.slavikjunior.kache.core.internal")
}
