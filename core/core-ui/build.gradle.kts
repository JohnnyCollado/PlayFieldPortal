plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace  = "com.playfieldportal.core.ui"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // Robolectric (the DragToScroll Compose test) needs the merged manifest + resources on the
    // test classpath, and a target SDK it can actually emulate. Same shape as feature-settings.
    testOptions {
        unitTests { isIncludeAndroidResources = true }
        // Robolectric 4.16 emulates up to SDK 36; a library module otherwise inherits
        // compileSdk (37), which it rejects. Unit tests only — the published library is unchanged.
        targetSdk = 36
    }
}

dependencies {
    api(project(":core:core-domain"))
    // Pure JVM, no Android weight. Motion-wallpaper caps (MotionLimits) live here because the
    // desktop Theme Studio authors motion wallpapers too and must validate against the same
    // numbers — a second copy would drift into themes the launcher silently refuses.
    api(project(":core:theme-kit"))
    implementation(platform(libs.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.coil.compose)
    // MotionWallpaperBackground builds GIF/WebP ImageRequests with an explicit repeatCount
    // (the decoder itself is registered on the app-wide ImageLoader in feature-artwork).
    implementation(libs.coil.gif)
    // MotionWallpaperBackground: the looping video surface behind the XMB (user-supplied
    // MP4/WebM motion wallpapers, released — not paused — on every freeze path).
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.common)
    // For MenuSoundPlayer: @Inject/@Singleton + @ApplicationContext annotations on the classpath.
    // The app module's Hilt processor does the code-gen, so no Hilt plugin/KSP needed here.
    implementation(libs.hilt.android)
    implementation(libs.timber)

    testImplementation(libs.bundles.test.unit)
    // Compose UI tests run on the JVM via Robolectric (same pattern as feature-settings):
    // DragToScroll's scroll direction is only observable by actually dragging a composed node.
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // Registers ComponentActivity in the debug manifest so createAndroidComposeRule works
    debugImplementation(libs.compose.ui.test.manifest)
}

// Robolectric fetches its Android image over HTTPS. On Windows, HTTPS interception (Avast) means
// the JVM's bundled cacerts can't validate the chain, so the test JVM is pointed at the OS trust
// store, which does carry the interceptor's root. Same workaround as feature-settings.
tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
        systemProperty("javax.net.ssl.trustStoreType", "Windows-ROOT")
    }
}
