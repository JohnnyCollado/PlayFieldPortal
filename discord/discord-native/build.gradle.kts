plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace  = "com.playfieldportal.discord"
    compileSdk = 35

    // Pinned to the NDK version AGP 8.10.x defaults to, so native builds are reproducible across
    // machines and CI. Install with: sdkmanager "ndk;27.0.12077973"
    ndkVersion = "27.0.12077973"

    defaultConfig {
        minSdk = 29
        // Ship only the ABIs real TV boxes / handhelds use (decision: arm64 + armeabi-v7a).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        externalNativeBuild {
            cmake { cppFlags += "-std=c++17" }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Consume the Discord Social SDK's prefab C++ package (headers + import lib) from the aar.
    buildFeatures { prefab = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Vendored Discord Social SDK (Git LFS), resolved via the flatDir repo declared in
    // settings.gradle.kts. The aar bundles: the Java glue classes (DiscordSocialSdkInit,
    // NativeCalls, AuthenticationActivity), the prebuilt libdiscord_partner_sdk.so per ABI,
    // and the prefab package our CMake links against. Transitively packaged into the app.
    implementation(mapOf("name" to "discord_partner_sdk", "ext" to "aar"))
    // Krisp noise-cancellation (voice). Kept wired from M0 so the voice milestone needs no build change.
    implementation(mapOf("name" to "discord_partner_sdk_krisp", "ext" to "aar"))
    // Custom Tabs — required by the SDK's AuthenticationActivity (browser OAuth path).
    implementation(libs.androidx.browser)
    implementation(libs.timber)

    // Domain interface (DiscordSessionActivator) + DiscordConfig; Hilt binding; coroutines.
    implementation(project(":core:core-domain"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
}
