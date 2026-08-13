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

// Schema JSONs land in /schemas for migration tests; @Database(exportSchema = true) is
// meaningless without this output directory. Under the legacy DSL (android.newDsl=false) AGP 9
// casts library source sets to the removed com.android.build.gradle.api.AndroidLibrarySourceSet
// and throws, so reach the "test" source set through the new-DSL LibraryExtension interface —
// which the same source-set impl still implements — instead of the legacy accessor.
(extensions.getByName("android") as com.android.build.api.dsl.LibraryExtension)
    .sourceSets.named("test") {
        assets.srcDir("$projectDir/schemas")
    }

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Robolectric downloads its Android image over HTTPS. On Windows, Avast's SSL scanning intercepts
// the connection, so the test JVM is pointed at the OS trust store, which carries the interceptor's
// root; the JVM's bundled cacerts does not.
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
    implementation(project(":core:theme-kit"))
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
    testImplementation(libs.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.ktor.client.mock)
}
