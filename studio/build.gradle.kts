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

dependencies {
    implementation(project(":core:theme-kit"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // Same Material glyph set the launcher's item rows use — drives default icon-slot
    // rendering and the editable template export.
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.coroutines.swing)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(testFixtures(project(":core:theme-kit")))
}

compose.desktop {
    application {
        mainClass = "com.playfieldportal.studio.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "PlayField Theme Studio"
            packageVersion = "1.1.0"
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
        include("**/*.msi", "**/*.exe", "**/*.deb", "**/*.dmg", "**/*.pkg")
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
    it.name == "packageReleaseDistributionForCurrentOS" || it.name == "packageReleaseMsi"
}.configureEach { finalizedBy(copyInstallerToDist) }
