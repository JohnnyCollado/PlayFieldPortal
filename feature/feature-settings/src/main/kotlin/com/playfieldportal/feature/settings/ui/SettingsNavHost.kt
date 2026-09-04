package com.playfieldportal.feature.settings.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.playfieldportal.core.domain.model.GamepadAction

// Every screen route SettingsNavHost resolves. Kept beside the `when` (whose branches are string
// literals) so the settings-hierarchy tests can verify that every L2 row id and every legacy
// direct-caller id resolves here. New screens MUST be added to both this set and the `when`.
val SETTINGS_SCREEN_ROUTES: Set<String> = setOf(
    "settings_initial_setup",
    "settings_initial_setup_first",
    "settings_library",
    "settings_windows_games",
    "settings_import_pc",
    "settings_music",
    "settings_video",
    "settings_photo",
    "settings_categories",
    "settings_artwork",
    "settings_artwork_import",
    "settings_achievements",
    // Achievements section entry points (Settings ▸ Achievements flyout) — first pass routes them
    // to the combined Shiba Coins screen; distinct ids keep the L2 list keys stable until
    // per-section focus targets land.
    "settings_achievements_player_card",
    "settings_achievements_credentials",
    "settings_achievements_local_windows",
    "settings_achievements_update",
    "settings_emulators",
    // Emulators section entry points — same first-pass note as the achievements ones above.
    "settings_emulators_installed",
    "settings_emulators_custom",
    "settings_emulators_retroarch",
    "settings_emulators_assign",
    "settings_themes",
    "settings_collections",
    "settings_display",
    "settings_controller",
    "settings_backup",
    "settings_logs",
    "settings_about",
    "settings_credits",
    // Hidden Items manager — moved out of Display; also the Library ▸ Hidden Games target.
    "settings_app_visibility",
)

// Routes a settings item ID to the correct full-screen settings composable.
// Shown as an overlay on top of XMBShell when the user selects a Settings sub-item.
@Composable
fun SettingsNavHost(
    screenId: String,
    onBack: () -> Unit,
    pendingGamepadAction: GamepadAction? = null,
    onGamepadActionConsumed: () -> Unit = {},
    onOpenColorSchemePicker: () -> Unit = {},
    onOpenXmbLayoutAdjust: () -> Unit = {},
    onAddAndroidApps: () -> Unit = {},
    onOpenPlayerStatus: () -> Unit = {},
    onOpenPlayerStatusFromSettings: () -> Unit = {},
    onOpenLibraryManager: () -> Unit = {},
    onGoToLibrary: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    CompositionLocalProvider(
        LocalSettingsPendingAction provides pendingGamepadAction,
        LocalSettingsActionConsumed provides onGamepadActionConsumed,
    ) {
        when (screenId) {
            "settings_initial_setup" -> InitialSetupScreen(
                onBack = onBack,
                onOpenLibraryManager = onOpenLibraryManager,
                onGoToLibrary = onGoToLibrary,
                modifier = modifier,
            )
            // The automatic first-run variant: Back cannot exit from the Welcome page.
            "settings_initial_setup_first" -> InitialSetupScreen(
                onBack = onBack,
                firstRun = true,
                onOpenLibraryManager = onOpenLibraryManager,
                onGoToLibrary = onGoToLibrary,
                modifier = modifier,
            )
            "settings_library"    -> LibraryManagerScreen(onBack = onBack, onAddAndroidApps = onAddAndroidApps, modifier = modifier)
            "settings_windows_games" -> LibraryManagerScreen(
                onBack = onBack,
                onAddAndroidApps = onAddAndroidApps,
                startAtWindowsCard = true,
                modifier = modifier,
            )
            // Library Manager opened straight into its Import PC Games section (games context menu).
            "settings_import_pc"  -> LibraryManagerScreen(onBack = onBack, onAddAndroidApps = onAddAndroidApps, startInImportPc = true, modifier = modifier)
            "settings_music"      -> MusicSettingsScreen(onBack = onBack, modifier = modifier)
            "settings_video"      -> VideoSettingsScreen(onBack = onBack, modifier = modifier)
            "settings_photo"      -> PhotoSettingsScreen(onBack = onBack, modifier = modifier)
            "settings_categories" -> CategoryManagerScreen(onBack = onBack, modifier = modifier)
            "settings_artwork"    -> ArtworkSettingsScreen(onBack = onBack, modifier = modifier)
            "settings_artwork_import" -> ArtworkImportScreen(onBack = onBack, modifier = modifier)
            "settings_achievements" -> AchievementsSettingsScreen(onBack = onBack, onOpenPlayerStatus = onOpenPlayerStatus, modifier = modifier)
            "settings_achievements_player_card" -> {
                // This branch is intentionally a tiny launch surface: selection opens the host-owned
                // Player Status overlay rather than composing the combined Achievements settings page.
                androidx.compose.runtime.LaunchedEffect(Unit) { onOpenPlayerStatusFromSettings() }
            }
            "settings_achievements_credentials" -> AchievementsSettingsScreen(
                onBack = onBack, onOpenPlayerStatus = onOpenPlayerStatus, section = AchievementsSettingsSection.PROVIDER_CREDENTIALS, modifier = modifier,
            )
            "settings_achievements_local_windows" -> AchievementsSettingsScreen(
                onBack = onBack, onOpenPlayerStatus = onOpenPlayerStatus, section = AchievementsSettingsSection.LOCAL_WINDOWS, modifier = modifier,
            )
            "settings_achievements_update" -> AchievementsSettingsScreen(
                onBack = onBack, onOpenPlayerStatus = onOpenPlayerStatus, section = AchievementsSettingsSection.UPDATE, modifier = modifier,
            )
            "settings_emulators"  -> EmulatorsSettingsScreen(onBack = onBack, modifier = modifier)
            "settings_emulators_installed" -> EmulatorsSettingsScreen(onBack = onBack, section = EmulatorSettingsSection.INSTALLED, modifier = modifier)
            "settings_emulators_custom" -> EmulatorsSettingsScreen(onBack = onBack, section = EmulatorSettingsSection.CUSTOM, modifier = modifier)
            "settings_emulators_retroarch" -> EmulatorsSettingsScreen(onBack = onBack, section = EmulatorSettingsSection.RETROARCH, modifier = modifier)
            "settings_emulators_assign" -> EmulatorAssignmentScreen(onBack = onBack, modifier = modifier)
            "settings_themes"     -> ThemesSettingsScreen(
                onBack = onBack,
                onOpenColorSchemePicker = onOpenColorSchemePicker,
                modifier = modifier,
            )
            "settings_collections" -> CollectionsSettingsScreen(onBack = onBack, modifier = modifier)
            "settings_display"    -> DisplaySettingsScreen(
                onBack = onBack,
                onOpenXmbLayoutAdjust = onOpenXmbLayoutAdjust,
                modifier = modifier,
            )
            "settings_controller" -> ControllerSettingsScreen(onBack = onBack, modifier = modifier)
            "settings_backup"     -> BackupSettingsScreen(onBack = onBack, modifier = modifier)
            "settings_logs"       -> LogsSettingsScreen(onBack = onBack, modifier = modifier)
            "settings_about"      -> AboutSettingsScreen(onBack = onBack, modifier = modifier)
            "settings_credits"    -> CreditsSettingsScreen(onBack = onBack, modifier = modifier)
            "settings_app_visibility" -> AppVisibilitySettingsScreen(onBack = onBack, modifier = modifier)
        }
    }
}
