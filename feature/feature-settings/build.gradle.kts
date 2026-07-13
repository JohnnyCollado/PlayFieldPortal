plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace  = "com.playfieldportal.feature.settings"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
    }
    // (No hardcoded VERSION_NAME/VERSION_CODE here anymore — the About screen reads the real
    // installed version from PackageManager, so it can never go stale again.)
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

    implementation(project(":core:core-common"))
    implementation(project(":core:core-domain"))
    implementation(project(":core:core-data"))
    implementation(project(":core:core-ui"))
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
}
