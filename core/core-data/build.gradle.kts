plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace  = "com.playfieldportal.core.data"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

// Robolectric downloads its Android image over HTTPS; the test JVM must trust the Windows cert
// store too (Avast SSL scanning intercepts HTTPS), matching the systemProp in gradle.properties.
tasks.withType<Test>().configureEach {
    systemProperty("javax.net.ssl.trustStoreType", "Windows-ROOT")
}

dependencies {
    implementation(project(":core:core-domain"))
    implementation(project(":core:core-common"))

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)
    implementation(libs.workmanager.ktx)
    implementation(libs.hilt.android)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)

    // Ktor — Discord OAuth2 device-authorization grant (QR login) over HTTPS.
    implementation(libs.bundles.ktor)

    testImplementation(libs.bundles.test.unit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.ktor.client.mock)
}
