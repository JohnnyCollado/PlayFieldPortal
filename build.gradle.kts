// Top-level build file
plugins {
    alias(libs.plugins.android.application)     apply false
    alias(libs.plugins.android.library)         apply false
    alias(libs.plugins.kotlin.jvm)              apply false
    alias(libs.plugins.kotlin.compose)          apply false
    alias(libs.plugins.kotlin.serialization)    apply false
    alias(libs.plugins.ksp)                     apply false
    alias(libs.plugins.hilt)                    apply false
    alias(libs.plugins.jetbrains.compose)       apply false
}

// Test JVMs get an agent appended to the boot classpath (MockK's inline mocking), which makes the
// JVM drop class-data sharing and print a "Sharing is only supported for boot loader classes"
// warning per test process. Turning sharing off up front is the same runtime, minus the noise.
subprojects {
    tasks.withType<Test>().configureEach {
        jvmArgs("-Xshare:off")
    }
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
