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

// Robolectric fetches its Android image over HTTPS; the test JVM must trust the Windows cert store
// too (Avast intercepts HTTPS), matching the systemProp in gradle.properties.
tasks.withType<Test>().configureEach {
    systemProperty("javax.net.ssl.trustStoreType", "Windows-ROOT")
}

dependencies {
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.bundles.test.unit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
