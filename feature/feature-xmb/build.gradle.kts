plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace  = "com.playfieldportal.feature.xmb"
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
    ksp(libs.hilt.compiler)

    implementation(libs.coil.compose)
    implementation(libs.accompanist.drawablepainter)
    implementation(libs.datastore.preferences)
    implementation(libs.material.icons.extended)
    // Built-in video player (Media3 ExoPlayer + PlayerView)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)

    implementation(project(":core:theme-kit"))
    implementation(project(":core:core-common"))
    implementation(project(":core:core-domain"))
    implementation(project(":core:core-data"))
    implementation(project(":core:core-navigation"))
    implementation(project(":core:core-ui"))
    implementation(project(":feature:feature-appbar"))
    implementation(project(":feature:feature-settings"))
    implementation(project(":feature:feature-launcher"))
    implementation(project(":feature:feature-artwork"))
    implementation(project(":feature:feature-achievements"))
    implementation(project(":feature:feature-library"))
    // Discord Social section — QR login screen. The native SDK bridge is not referenced here; the
    // app module owns it (full flavor only), so feature-xmb stays flavor-agnostic.
    implementation(project(":feature:feature-social"))

    testImplementation(libs.bundles.test.unit)
    testImplementation(libs.hilt.android.testing)
    // Compose UI tests run on the JVM via Robolectric (same pattern as feature-settings)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Registers ComponentActivity in the debug manifest so createAndroidComposeRule works
    debugImplementation(libs.compose.ui.test.manifest)
}
