// Top-level build file
plugins {
    alias(libs.plugins.android.application)     apply false
    alias(libs.plugins.android.library)         apply false
    alias(libs.plugins.kotlin.android)          apply false
    alias(libs.plugins.kotlin.jvm)              apply false
    alias(libs.plugins.kotlin.compose)          apply false
    alias(libs.plugins.kotlin.serialization)    apply false
    alias(libs.plugins.ksp)                     apply false
    alias(libs.plugins.hilt)                    apply false
    alias(libs.plugins.jetbrains.compose)       apply false
}

// One command to build every shippable release artifact into <root>/dist (gitignored):
// the full + lite launcher APKs and the Theme Studio installer for the current OS. The
// per-module copy tasks (finalizing each release build) do the actual placing.
tasks.register("dist") {
    group = "distribution"
    description = "Builds full+lite release APKs and the Theme Studio installer into <root>/dist."
    dependsOn(
        ":app:assembleFullRelease",
        ":app:assembleLiteRelease",
        ":studio:packageReleaseDistributionForCurrentOS",
    )
}
