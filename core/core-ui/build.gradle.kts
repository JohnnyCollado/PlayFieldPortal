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
}
