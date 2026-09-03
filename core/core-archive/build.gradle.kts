// core-archive: pure-JVM archive ingestion. Owns the bomb/slip policy for every ZIP this project
// reads, so a hardening fix lands once instead of three times.
//
// Pure JVM on purpose, and for the same reason as :core:theme-kit — the desktop Theme Studio reads
// .pfptheme bundles through PfpThemeCodec, so this module must never grow an Android dependency.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
