plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace  = "com.playfieldportal.feature.achievements"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    testOptions {
        unitTests.all { test ->
            // Forward the RaHashVerification harness's -Dra.hash.* flags from the Gradle JVM into the
            // forked test JVM (Gradle does not propagate them by default). Env vars are inherited as-is.
            listOf("ra.hash.manifest", "ra.hash.spec", "ra.hash.out").forEach { key ->
                System.getProperty(key)?.let { test.systemProperty(key, it) }
            }
        }
    }
}

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Steam library import runs as a WorkManager job (survives backgrounding, notification
    // progress, cancellable) — same pattern as feature-artwork's scrape worker.
    implementation(libs.workmanager.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.timber)
    implementation(libs.xz) // LZMA decoder for CHD cdlz hunks

    // Retrofit — the Steam Web API client (provider/steam island). DTOs stay kotlinx-serialization.
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)

    // Official RetroAchievements Kotlin client. Pulls Retrofit/OkHttp/Gson transitively; kept
    // strictly inside the provider/retro island (RaRemoteDataSource). Never used outside it.
    implementation(libs.retroachievements.api)

    implementation(project(":core:core-common"))
    implementation(project(":core:core-ui")) // BackgroundTaskNotifier for the import worker
    implementation(project(":core:core-domain"))
    implementation(project(":core:core-data"))
    implementation(project(":feature:feature-artwork")) // SteamGridDB client for Steam-id resolution

    testImplementation(libs.bundles.test.unit)
}
