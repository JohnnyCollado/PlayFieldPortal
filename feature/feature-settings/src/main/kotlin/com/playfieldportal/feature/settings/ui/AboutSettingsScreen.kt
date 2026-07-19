package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The real installed version, from PackageManager — a library module's BuildConfig can't
    // know the app's versionName/versionCode (the old hardcoded copy here went stale).
    val context = LocalContext.current
    val packageInfo = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
    }
    val versionName = packageInfo?.versionName ?: "unknown"
    val versionCode = packageInfo?.longVersionCode?.toString() ?: "unknown"
    // Flavor from the installed package id: the lite build's applicationId carries a ".lite"
    // segment (see app/build.gradle.kts productFlavors). Full ships the Discord Social SDK; lite
    // omits it — so the edition is a real, user-visible difference worth surfacing here.
    val isLite = remember(context) { context.packageName.contains(".lite") }

    // Value rows are focusable, so the cursor walks the list and focus-driven scrolling brings
    // each row into view — no manual scroll interception needed.
    SettingsScaffold(
        title    = "Settings",
        subtitle = "About",
        onBack   = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsGroup("Play Field Portal")

            SettingsValueRow(label = "Version",      value = versionName)
            SettingsValueRow(label = "Build",        value = versionCode)
            SettingsValueRow(label = "Edition",      value = if (isLite) "Lite" else "Full")
            SettingsValueRow(label = "Min Android",  value = "Android 10 (API 29)")
            SettingsValueRow(label = "Target",       value = "Android 15 (API 35)")

            SettingsGroup("Open Source")

            SettingsValueRow(label = "Jetpack Compose",   value = "UI framework")
            SettingsValueRow(label = "Room",              value = "Local database")
            SettingsValueRow(label = "Hilt",              value = "Dependency injection")
            SettingsValueRow(label = "Ktor",              value = "Network client")
            SettingsValueRow(label = "Coil",              value = "Image loading")
            SettingsValueRow(label = "Media3",            value = "Video snaps & playback")
            SettingsValueRow(label = "WorkManager",       value = "Background tasks")
            SettingsValueRow(label = "Timber",            value = "Logging")

            SettingsGroup("Credits")

            SettingsValueRow(label = "Artwork & Media", value = "ScreenScraper · SteamGridDB")
            SettingsValueRow(label = "Metadata",        value = "ScreenScraper · TheGamesDB · IGDB")
            SettingsValueRow(label = "Inspired by",     value = "Sony PSP XMB")
            SettingsValueRow(label = "See also",        value = "Settings ▸ Credits")

            SettingsGroup("Legal")

            SettingsRow(
                label    = "Play Field Portal is an independent fan project, not affiliated with Sony Interactive Entertainment.",
                sublabel = "PlayStation, PSP and XMB are trademarks of Sony Interactive Entertainment Inc.",
            )
        }
    }
}
