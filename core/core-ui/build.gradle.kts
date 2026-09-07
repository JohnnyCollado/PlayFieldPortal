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
    // SystemIconsTest pins the SYSICON_PLATFORM_IDS registry (theme-kit, pure JVM) against
    // the R8-safe static when — test-only, so the module graph stays Android-first.
    testImplementation(project(":core:theme-kit"))
}
