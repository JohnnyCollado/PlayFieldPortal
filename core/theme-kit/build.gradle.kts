// theme-kit: pure-JVM theme parsing/conversion core, shared between the Android launcher and
// the desktop Theme Studio companion (Compose Desktop). No Android dependencies allowed here —
// image bytes go in/out as ByteArray and the frontends do their own platform image codecs.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
