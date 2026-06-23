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
        buildConfig = true
    }
    buildTypes {
        debug {
            buildConfigField("String", "VERSION_NAME", "\"0.1.0-alpha-debug\"")
            buildConfigField("int", "VERSION_CODE", "1")
        }
        release {
            buildConfigField("String", "VERSION_NAME", "\"0.1.0-alpha\"")
            buildConfigField("int", "VERSION_CODE", "1")
        }
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

    implementation(project(":core:core-common"))
    implementation(project(":core:core-domain"))
    implementation(project(":core:core-data"))
    implementation(project(":core:core-ui"))
    implementation(project(":feature:feature-artwork"))
    // EmulatorProfileRepository
    implementation(project(":feature:feature-launcher"))
    // BackupManager and workers
    implementation(project(":feature:feature-backup"))
    // RomScanner, PlatformExtensionMap, DiscImageResolver
    implementation(project(":feature:feature-library"))
    // ThemeRepository, XmbThemeLoader
    implementation(project(":feature:feature-themes"))

    testImplementation(libs.bundles.test.unit)
    testImplementation(libs.hilt.android.testing)
}
