plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace  = "com.playfieldportal.feature.achievements"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

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
    // api-kotlin 2.0.0 requests logging-interceptor 4.12.0, which calls OkHttp internals that the
    // rest of the app no longer resolves to (ktor-client-okhttp 3.5.2 lifts OkHttp to 5.3.2).
    // Its only use is the `debugging = true` branch RaClientFactory keeps off, but a 4.x
    // interceptor against a 5.x core is a NoSuchMethodError waiting for whoever flips that flag.
    implementation(libs.okhttp.logging.interceptor)

    implementation(project(":core:core-common"))
    implementation(project(":core:core-ui")) // BackgroundTaskNotifier for the import worker
    implementation(project(":core:core-domain"))
    implementation(project(":core:core-data"))
    implementation(project(":feature:feature-artwork")) // SteamGridDB client for Steam-id resolution
    implementation(project(":feature:feature-launcher")) // PcGameAchievementLinker seam (shortcut imports)

    testImplementation(libs.bundles.test.unit)
}
