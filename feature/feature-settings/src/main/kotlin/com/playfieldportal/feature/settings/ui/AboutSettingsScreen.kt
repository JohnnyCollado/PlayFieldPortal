package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.domain.model.GamepadAction
import kotlinx.coroutines.launch

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

    // Pure info screen — no interactive rows for the scaffold's focus navigation to walk, so
    // Up/Down scroll the column directly instead.
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val stepPx = with(LocalDensity.current) { 120.dp.toPx() }

    SettingsScaffold(
        title    = "Settings",
        subtitle = "About",
        onBack   = onBack,
        modifier = modifier,
        onInterceptAction = { action ->
            when (action) {
                GamepadAction.NAVIGATE_UP   -> { scope.launch { scrollState.animateScrollBy(-stepPx) }; true }
                GamepadAction.NAVIGATE_DOWN -> { scope.launch { scrollState.animateScrollBy(stepPx) }; true }
                else -> false
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            SettingsGroup("Play Field Portal")

            SettingsValueRow(label = "Version",      value = versionName)
            SettingsValueRow(label = "Build",        value = versionCode)
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
