// Theme Studio: the desktop companion app (Compose Multiplatform Desktop, Windows/Linux/macOS).
// Pure JVM by construction — it shares theme parsing/conversion with the launcher through
// :core:theme-kit and must never grow an Android dependency.
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)      // Kotlin 2.0 Compose compiler
    alias(libs.plugins.jetbrains.compose)   // CMP artifacts + desktop packaging DSL
}

kotlin {
    jvmToolchain(17)
}

// JavaCPP's name for this machine's platform: which FFmpeg natives the Studio runs and ships with.
val javacppPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("win") -> "windows-x86_64"
        os.contains("mac") -> if (arm) "macosx-arm64" else "macosx-x86_64"
        else -> if (arm) "linux-arm64" else "linux-x86_64"
    }
}

dependencies {
    implementation(project(":core:theme-kit"))
    // The launcher's own colour rules (contrast, text roles, palettes, tokens): the preview runs
    // them rather than a copy.
    implementation(project(":core:theme-render"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.mp.material3)
    // Same Material glyph set the launcher's item rows use — drives default icon-slot
    // rendering and the editable template export.
    implementation(libs.compose.mp.icons.extended)
    // Byte-array decoders for the bundled XMB art (StudioIconSet): the supported replacement for
    // the deprecated androidx.compose.ui.res loaders. Same version as the Compose plugin.
    implementation(libs.compose.mp.resources)
    implementation(libs.kotlinx.coroutines.swing)
    // JsonObject is part of PfpThemeBundle's public API (manifestExtras); theme-kit keeps the
    // library `implementation`, so the Studio, which carries extras through its state, names it too.
    implementation(libs.kotlinx.serialization.json)
    // The motion-wallpaper import gate (VideoCodecs: header probe, poster frame). Pure Java, so a
    // hostile pick fails with an exception before any native decoder ever sees it.
    implementation(libs.jcodec)
    // AWTUtil (Picture -> BufferedImage) ships separately from the codec core, because the core
    // itself is AWT-free. Both artifacts share the jcodec version.
    implementation(libs.jcodec.javase)
    // Live preview playback (FfmpegFrameReader). JCodec decodes in plain Java on one core and
    // cannot keep a 1080p clip at 30 fps; FFmpeg decodes it on every core. Only JavaCV's
    // FFmpegFrameGrabber/FFmpegFrameFilter are used, so its own dependency tree (OpenCV,
    // OpenBLAS, Tesseract, ...) stays out; the FFmpeg and JavaCPP natives are bundled for the
    // OS the Studio is built and packaged on, like compose.desktop.currentOs.
    implementation(libs.javacv) { isTransitive = false }
    implementation(libs.javacpp)
    implementation(libs.ffmpeg)
    runtimeOnly(variantOf(libs.javacpp) { classifier(javacppPlatform) })
    runtimeOnly(variantOf(libs.ffmpeg) { classifier(javacppPlatform) })

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:theme-kit")))
}

compose.desktop {
    application {
        mainClass = "com.playfieldportal.studio.MainKt"
        // Disable release minification: Compose bundles a Java 21 JBR the default ProGuard can't
        // read, and minifying strips reflection/entry-point classes the app needs at startup
        // ("Failed to launch JVM"). An unminified distribution is larger but launches reliably.
        buildTypes.release.proguard {
            isEnabled.set(false)
        }
        nativeDistributions {
            // Exe is the Windows default we ship (build-theme-studio-installer.bat drives it);
            // Msi stays for managed/silent deployment. Both come out of the same jpackage run
            // and both need the WiX Toolset on PATH.
            targetFormats(TargetFormat.Dmg, TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Deb)
            // JavaCPP (the FFmpeg bindings) reaches for sun.misc.Unsafe; the jlinked runtime
            // leaves jdk.unsupported out unless asked.
            modules("jdk.unsupported")
            packageName = "PlayField Theme Studio"
            packageVersion = "1.2.0"
            description = "Create, convert, and share PlayFieldPortal XMB themes"

            windows {
                // Desktop + Start Menu shortcuts, and let the user pick the install dir.
                shortcut = true
                menu = true
                menuGroup = "PlayField Theme Studio"
                dirChooser = true
                // Stable MSI UpgradeCode: MUST NEVER CHANGE. It ties every future installer to
                // this product in Add/Remove Programs, so upgrades replace the existing install
                // (and its uninstaller entry) instead of stacking duplicates.
                upgradeUuid = "AD54E734-C014-49C8-821F-4003D0B61439"
            }
        }
    }
}

// ── Release installer collection ─────────────────────────────────────────────
// Drop the packaged release installer for the current OS into <root>/dist (gitignored),
// flattened out of its format subdir, so it sits alongside the launcher APKs.
val distDir = rootProject.layout.projectDirectory.dir("dist")
val copyInstallerToDist = tasks.register<Copy>("copyReleaseInstallerToDist") {
    from(layout.buildDirectory.dir("compose/binaries/main-release")) {
        // Scoped to the per-format subdirs, NOT "**/*.exe": the app-image that jpackage builds
        // first lands in main-release/app/ and contains the application LAUNCHER
        // "PlayField Theme Studio.exe". Flattened into dist next to the installer that would be
        // an easy exe to hand someone by mistake — it only runs from its own install tree.
        include("exe/**/*.exe", "msi/**/*.msi", "deb/**/*.deb", "dmg/**/*.dmg", "pkg/**/*.pkg")
    }
    // Flatten out of the format subdir and normalize spaces to hyphens, matching the launcher
    // APK naming in dist (e.g. "PlayField Theme Studio-1.1.0.msi" -> PlayField-Theme-Studio-1.1.0.msi).
    eachFile { path = name.replace(" ", "-") }
    includeEmptyDirs = false
    into(distDir)
    // Always refresh the shared dist drop folder even when the installer is up-to-date.
    outputs.upToDateWhen { false }
}
tasks.matching {
    it.name == "packageReleaseDistributionForCurrentOS" ||
        it.name == "packageReleaseExe" ||
        it.name == "packageReleaseMsi"
}.configureEach { finalizedBy(copyInstallerToDist) }
