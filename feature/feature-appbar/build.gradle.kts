plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace  = "com.playfieldportal.feature.appbar"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // Robolectric Compose UI tests need the merged manifest (ComponentActivity registration).
    testOptions {
        unitTests { isIncludeAndroidResources = true }
    }
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
    implementation(libs.coil.compose)
    implementation(libs.accompanist.drawablepainter)
    // Compose @Preview rendering: Studio's Layoutlib loads androidx.compose.ui.tooling.
    // ComposeViewAdapter from ui-tooling when rendering a preview declared in this module.
    // (ui-tooling-preview — the @Preview annotations — already rides in bundles.compose.)
    debugImplementation(libs.compose.ui.tooling)
    ksp(libs.hilt.compiler)

    implementation(project(":core:core-common"))
    implementation(project(":core:core-domain"))
    implementation(project(":core:core-data"))
    implementation(project(":core:core-ui"))

    testImplementation(libs.bundles.test.unit)
    // Compose UI tests run on the JVM via Robolectric (same pattern as feature-settings)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Registers ComponentActivity in the debug manifest so createAndroidComposeRule works
    debugImplementation(libs.compose.ui.test.manifest)
}
