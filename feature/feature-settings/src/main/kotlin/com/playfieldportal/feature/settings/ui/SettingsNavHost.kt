package com.playfieldportal.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.playfieldportal.core.domain.model.GamepadAction

// Routes a settings item ID to the correct full-screen settings composable.
// Shown as an overlay on top of XMBShell when the user selects a Settings sub-item.
@Composable
fun SettingsNavHost(
    screenId: String,
    onBack: () -> Unit,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    onOpenColorSchemePicker: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(
        LocalSettingsPendingAction provides pendingGamepadAction,
        LocalSettingsActionConsumed provides onGamepadActionConsumed,
    ) {
        when (screenId) {
            "settings_library"    -> LibraryManagerScreen(onBack = onBack, modifier = modifier)
            "settings_music"      -> MusicSettingsScreen(onBack = onBack, modifier = modifier)
            "settings_categories" -> CategoryManagerScreen(onBack = onBack, modifier = modifier)
            "settings_artwork"    -> ArtworkSettingsScreen(onBack = onBack, modifier = modifier)
            "settings_emulators"  -> EmulatorsSettingsScreen(onBack = onBack, modifier = modifier)
            "settings_themes"     -> ThemesSettingsScreen(
                onBack = onBack,
                onOpenColorSchemePicker = onOpenColorSchemePicker,
                modifier = modifier,
            )
            "settings_collections" -> CollectionsSettingsScreen(onBack = onBack, modifier = modifier)
            "settings_display"    -> DisplaySettingsScreen(onBack = onBack, modifier = modifier)
            "settings_controller" -> ControllerSettingsScreen(onBack = onBack, modifier = modifier)
            "settings_backup"     -> BackupSettingsScreen(onBack = onBack, modifier = modifier)
            "settings_logs"       -> LogsSettingsScreen(onBack = onBack, modifier = modifier)
            "settings_about"      -> AboutSettingsScreen(onBack = onBack, modifier = modifier)
            "settings_credits"    -> CreditsSettingsScreen(onBack = onBack, modifier = modifier)
        }
    }
}
