package com.playfieldportal.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// Routes a settings item ID to the correct full-screen settings composable.
// Shown as an overlay on top of XMBShell when the user selects a Settings sub-item.
@Composable
fun SettingsNavHost(
    screenId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (screenId) {
        "settings_library"   -> LibrarySettingsScreen(onBack = onBack, modifier = modifier)
        "settings_artwork"   -> ArtworkSettingsScreen(onBack = onBack, modifier = modifier)
        "settings_emulators" -> EmulatorsSettingsScreen(onBack = onBack, modifier = modifier)
        "settings_themes"    -> ThemesSettingsScreen(onBack = onBack, modifier = modifier)
        "settings_display"   -> DisplaySettingsScreen(onBack = onBack, modifier = modifier)
        "settings_controller"-> ControllerSettingsScreen(onBack = onBack, modifier = modifier)
        "settings_backup"    -> BackupSettingsScreen(onBack = onBack, modifier = modifier)
        "settings_logs"      -> LogsSettingsScreen(onBack = onBack, modifier = modifier)
        "settings_about"     -> AboutSettingsScreen(onBack = onBack, modifier = modifier)
    }
}
