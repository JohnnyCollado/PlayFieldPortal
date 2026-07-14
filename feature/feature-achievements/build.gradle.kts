plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace  = "com.playfieldportal.feature.achievements"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests.all { test ->
            // Forward the RaHashVerification harness's -Dra.hash.* flags from the Gradle JVM into the
            // forked test JVM (Gradle does not propagate them by default). Env vars are inherited as-is.
            listOf("ra.hash.manifest", "ra.hash.spec", "ra.hash.out").forEach { key ->
                System.getProperty(key)?.let { test.systemProperty(key, it) }
            }
        }
    }
}

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.ktor)
    implementation(libs.timber)
    implementation(libs.xz) // LZMA decoder for CHD cdlz hunks

    implementation(project(":core:core-common"))
    implementation(project(":core:core-domain"))
    implementation(project(":core:core-data"))
    implementation(project(":feature:feature-artwork")) // SteamGridDB client for Steam-id resolution

    testImplementation(libs.bundles.test.unit)
    testImplementation(libs.ktor.client.mock)
}
