// core-navigation: generic controller navigation engine (spec:
// PlayFieldPortal_Unified_Navigation_Architecture.md). Pure JVM — no Android/Compose
// dependencies allowed, so the engine stays unit-testable without a Compose hierarchy.
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
    testImplementation(libs.kotlinx.coroutines.test)
}
