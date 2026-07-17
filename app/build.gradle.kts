import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Load release signing config from keystore.properties (gitignored).
// Absent on machines without the signing key — release build then stays unsigned.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.playfieldportal.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.playfieldportal.launcher"
        minSdk = 29           // Android 10 — Winlator minimum
        targetSdk = 35
        versionCode = 7
        versionName = "1.1.0"
    }

    signingConfigs {
        create("release") {
            if (keystorePropertiesFile.exists()) {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true
            buildConfigField("boolean", "ENABLE_PERF_OVERLAY", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "ENABLE_PERF_OVERLAY", "false")
            // Sign with the release key only when keystore.properties is present.
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // "full" ships the Discord Social SDK; "lite" excludes it (see the flavor-scoped dependency
    // below) for a much smaller download. The lite flavor hides the Social section at runtime via
    // DiscordSessionActivator.sdkAvailable and binds no-op Discord components.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
        }
        create("lite") {
            dimension = "distribution"
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.splashscreen)
    implementation(libs.bundles.lifecycle)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.timber)
    implementation(libs.accompanist.systemuicontroller)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.workmanager.ktx)
    implementation(libs.datastore.preferences)

    // All feature & core modules
    implementation(project(":core:core-common"))
    implementation(project(":core:core-domain"))
    implementation(project(":core:core-data"))
    implementation(project(":core:core-ui"))
    implementation(project(":feature:feature-xmb"))
    implementation(project(":feature:feature-library"))
    implementation(project(":feature:feature-launcher"))
    implementation(project(":feature:feature-artwork"))
    implementation(project(":feature:feature-themes"))
    implementation(project(":feature:feature-settings"))
    implementation(project(":feature:feature-appbar"))
    implementation(project(":feature:feature-backup"))
    implementation(project(":feature:feature-social"))
    // Achievement session controller (in-game popup watcher start/stop from MainActivity)
    implementation(project(":feature:feature-achievements"))
    // Native Discord SDK bridge — provides the DiscordSessionActivator Hilt binding + the .so.
    // Discord SDK only in the "full" flavor — the native libs (WebRTC + Krisp) are the largest part
    // of the download, so "lite" omits them entirely.
    "fullImplementation"(project(":discord:discord-native"))

    debugImplementation(libs.compose.ui.tooling)
}

// ── Release artifact collection ──────────────────────────────────────────────
// Mirror every release APK into <root>/dist so all shippable builds land in one
// predictable, gitignored place. Each assemble{Flavor}Release finalizes into its
// copy, so the APK is always in dist after a release build (or `./gradlew dist`).
val distDir = rootProject.layout.projectDirectory.dir("dist")
val appVersion = android.defaultConfig.versionName ?: "0"
listOf("Full", "Lite").forEach { flavor ->
    val copyApk = tasks.register<Copy>("copy${flavor}ReleaseToDist") {
        from(layout.buildDirectory.dir("outputs/apk/${flavor.lowercase()}/release"))
        include("*.apk")
        // Clean, versioned name in dist (e.g. PlayFieldPortal-1.1.0-full.apk).
        rename { "PlayFieldPortal-$appVersion-${flavor.lowercase()}.apk" }
        into(distDir)
        // dist is a shared, versioned drop folder — always refresh so the current build is
        // guaranteed present even when the APK itself is up-to-date.
        outputs.upToDateWhen { false }
    }
    tasks.matching { it.name == "assemble${flavor}Release" }.configureEach {
        finalizedBy(copyApk)
    }
}
