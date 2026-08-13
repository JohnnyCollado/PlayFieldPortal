plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace  = "com.playfieldportal.core.common"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Robolectric fetches its Android image over HTTPS. On Windows, HTTPS interception (Avast) means
// the JVM's bundled cacerts can't validate the chain, so the test JVM is pointed at the OS trust
// store, which does carry the interceptor's root.
//
// Windows-only on purpose: the "Windows-ROOT" store type does not exist on Linux or macOS, and
// setting it there makes the JVM fail to load any trust store at all — breaking TLS rather than
// fixing it. Guarding on the OS is what lets this repo build on a non-Windows machine.
tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        systemProperty("javax.net.ssl.trustStoreType", "Windows-ROOT")
    }
}

dependencies {
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.bundles.test.unit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
