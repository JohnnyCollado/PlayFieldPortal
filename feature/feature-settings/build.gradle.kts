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
        // BuildConfig.DEBUG gates the debug-only credentials file row in Settings ▸ Artwork.
        buildConfig = true
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

    // One JVM per test class.
    //
    // This module's ViewModels share a single process-global `pfp_prefs` DataStore, whose write
    // actor is strictly serial: once any test class leaves it in a bad state, every later class
    // that touches it either fails at a bounded wait or blocks forever at an unbounded one.
    // AudioSettingsViewModelTest documents that hazard at length and defends itself against its
    // own writes, but a class cannot defend against what a PREVIOUS class left behind — and the
    // symptom depends on test execution order, so adding an unrelated test class anywhere in the
    // module can surface it.
    //
    // A fresh JVM per class makes leaked process-global state structurally impossible instead of
    // relying on every author knowing the rule. It costs suite wall time (a fresh Robolectric
    // sandbox per class); that is the right trade for a module whose tests otherwise pass or fail
    // based on what ran before them.
    forkEvery = 1
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
