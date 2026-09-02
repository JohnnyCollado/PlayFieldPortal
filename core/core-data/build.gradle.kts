plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace  = "com.playfieldportal.core.data"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests { isIncludeAndroidResources = true }
        // Robolectric 4.16 emulates up to SDK 36. Library modules default targetSdk to
        // compileSdk (37), which Robolectric rejects outright, so pin the test target here.
        // This affects unit tests only — the published library is unchanged.
        targetSdk = 36
    }
}

// Schema JSONs land in /schemas for the migration tests; @Database(exportSchema = true) is
// meaningless without this output directory on the test source set.
//
// Configured through the new-DSL LibraryExtension interface rather than the `android { }` block:
// the generated Kotlin DSL accessor is still typed to the legacy extension, so `sourceSets` there
// hands back a container whose configure block casts to the removed AndroidLibrarySourceSet and
// throws. The impl object implements both interfaces, so naming the new one is enough.
extensions.configure<com.android.build.api.dsl.LibraryExtension> {
    sourceSets.named("test") {
        assets.directories.add("$projectDir/schemas")
    }
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
