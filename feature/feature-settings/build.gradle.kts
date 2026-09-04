plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace  = "com.playfieldportal.feature.settings"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    // Robolectric (Compose UI tests) needs the merged manifest + resources on the test classpath
    testOptions {
        unitTests { isIncludeAndroidResources = true }
        // Robolectric 4.16 emulates up to SDK 36. Library modules default targetSdk to
        // compileSdk (37), which Robolectric rejects outright, so pin the test target here.
        // This affects unit tests only — the published library is unchanged.
        targetSdk = 36
    }
    // (No hardcoded VERSION_NAME/VERSION_CODE here anymore — the About screen reads the real
    // installed version from PackageManager, so it can never go stale again.)
}

// Robolectric fetches its Android image over HTTPS. On Windows, HTTPS interception (Avast) means
// the JVM's bundled cacerts can't validate the chain, so the test JVM is pointed at the OS trust
// store, which does carry the interceptor's root. Same workaround as feature-launcher/core-common.
tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        systemProperty("javax.net.ssl.trustStoreType", "Windows-ROOT")
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

    implementation(libs.datastore.preferences)
    implementation(libs.workmanager.ktx)
    implementation(libs.coil.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.material.icons.extended)

    implementation(project(":core:core-common"))
    implementation(project(":core:core-domain"))
    implementation(project(":core:core-data"))
    // XmbLayoutPreset auto-fit + XmbLayoutAdjustCodec for the wizard's XMB auto-fit opt-in
    implementation(project(":core:theme-kit"))
    implementation(project(":core:core-ui"))
    implementation(project(":core:core-navigation"))
    implementation(project(":feature:feature-artwork"))
    // AchievementCredentialsProvider + SteamAchievementsApi for the Shiba Coins connect screen
    implementation(project(":feature:feature-achievements"))
    // EmulatorProfileRepository
    implementation(project(":feature:feature-launcher"))
    // BackupManager and workers
    implementation(project(":feature:feature-backup"))
    // RomScanner, PlatformExtensionMap, DiscImageResolver
    implementation(project(":feature:feature-library"))
    // ThemeRepository, XmbThemeLoader
    implementation(project(":feature:feature-themes"))
    // InstalledAppRepository, AppCategoryRepository — powers the Hidden Apps manager
    implementation(project(":feature:feature-appbar"))

    testImplementation(libs.bundles.test.unit)
    testImplementation(libs.hilt.android.testing)
    // Compose UI tests run on the JVM via Robolectric (same pattern as core-data / feature-launcher)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    debugImplementation(libs.compose.ui.tooling)
    // Registers ComponentActivity in the debug manifest so createAndroidComposeRule works
    debugImplementation(libs.compose.ui.test.manifest)
}
