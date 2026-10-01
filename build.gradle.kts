import kotlinx.validation.ExperimentalBCVApi

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.androidx.room) apply false
    alias(libs.plugins.dokka)
    alias(libs.plugins.bcv)
}

@OptIn(ExperimentalBCVApi::class)
apiValidation {
    // Exclude modules without a stable public API or test-only modules
    ignoredPackages.add("io.github.slavikjunior.kache.core.internal")

    // KLib ABI validation covers the iOS targets. It is disabled by default, which
    // makes `klibApiCheck` report SKIPPED and leaves no golden ABI file behind, so
    // any change to the public API of an iOS target would pass unnoticed.
    klib {
        enabled = true
    }
}
