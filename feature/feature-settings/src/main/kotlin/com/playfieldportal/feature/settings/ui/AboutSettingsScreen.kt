package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.playfieldportal.feature.settings.BuildConfig

@Composable
fun AboutSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
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

            SettingsValueRow(label = "Version",     value = BuildConfig.VERSION_NAME)
            SettingsValueRow(label = "Build",        value = BuildConfig.VERSION_CODE.toString())
            SettingsValueRow(label = "Min Android",  value = "Android 10 (API 29)")
            SettingsValueRow(label = "Target",       value = "Android 15 (API 35)")

            SettingsGroup("Open Source")

            SettingsValueRow(label = "Jetpack Compose",   value = "UI framework")
            SettingsValueRow(label = "Room",              value = "Local database")
            SettingsValueRow(label = "Hilt",              value = "Dependency injection")
            SettingsValueRow(label = "Ktor",              value = "Network client")
            SettingsValueRow(label = "Coil",              value = "Image loading")
            SettingsValueRow(label = "WorkManager",       value = "Background tasks")
            SettingsValueRow(label = "Timber",            value = "Logging")

            SettingsGroup("Credits")

            SettingsValueRow(label = "Artwork",      value = "SteamGridDB")
            SettingsValueRow(label = "Inspired by",  value = "Sony PSP XMB")

            SettingsGroup("Legal")

            SettingsRow(
                label    = "Play Field Portal is not affiliated with Sony Interactive Entertainment.",
                sublabel = "PlayStation and XMB are trademarks of Sony Interactive Entertainment LLC.",
            )
        }
    }
}
