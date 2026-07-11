import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// ScreenScraper developer credentials: kept out of git in local.properties (CI injects via env).
//   screenscraper.devId=xxx
//   screenscraper.devPassword=yyy
// Empty values compile fine — the client treats missing dev credentials as "ScreenScraper disabled".
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun ssProp(key: String, env: String): String =
    (localProps.getProperty(key) ?: System.getenv(env) ?: "").replace("\"", "\\\"")

android {
    namespace  = "com.playfieldportal.feature.artwork"
    compileSdk = 35
    defaultConfig {
        minSdk = 29
        // String.valueOf(...) keeps these fields NON-constant: plain literals would be
        // compile-time constants that get inlined into consuming classes, and neither
        // incremental compilation nor the build cache reliably rebuilds consumers when only a
        // constant's value changes — editing local.properties then silently ships stale creds.
        buildConfigField("String", "SS_DEV_ID",       "String.valueOf(\"${ssProp("screenscraper.devId", "SS_DEV_ID")}\")")
        buildConfigField("String", "SS_DEV_PASSWORD", "String.valueOf(\"${ssProp("screenscraper.devPassword", "SS_DEV_PASSWORD")}\")")
        buildConfigField("String", "SS_SOFT_NAME",    "\"PlayFieldPortal\"")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.bundles.lifecycle)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.ktor)
    implementation(libs.coil.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.workmanager.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.compiler)
    ksp(libs.hilt.work.compiler)
    testImplementation(libs.bundles.test.unit)

    implementation(project(":core:core-common"))
    implementation(project(":core:core-domain"))
    implementation(project(":core:core-data"))
    implementation(project(":core:core-ui"))
}
