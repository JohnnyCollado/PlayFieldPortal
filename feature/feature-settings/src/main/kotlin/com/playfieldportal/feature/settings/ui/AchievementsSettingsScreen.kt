package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.playfieldportal.core.ui.achievement.ShibaPlayerCard
import com.playfieldportal.core.ui.theme.menuCursorEdge
import com.playfieldportal.feature.settings.viewmodel.AchievementsSettingsViewModel

enum class AchievementsSettingsSection { PROVIDER_CREDENTIALS, LOCAL_WINDOWS, UPDATE }

@Composable
fun AchievementsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenPlayerStatus: () -> Unit = {},
    section: AchievementsSettingsSection? = null,
    viewModel: AchievementsSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    var raUsernameDraft by remember(state.raUsername) { mutableStateOf("") }
    var raKeyDraft by remember(state.hasRetroAchievements) { mutableStateOf("") }
    var steamIdDraft by remember(state.steamId64) { mutableStateOf("") }
    var steamKeyDraft by remember(state.hasSteam) { mutableStateOf("") }
    var showLocalSteamWarning by remember { mutableStateOf(false) }
    var showGoldbergWarning by remember { mutableStateOf(false) }

    val credentialsEnabled = state.enabled
    val credentialsAlpha = if (credentialsEnabled) 1f else 0.45f

    SettingsScaffold(
        title    = "Settings",
        subtitle = when (section) {
            AchievementsSettingsSection.PROVIDER_CREDENTIALS -> "Achievements · Provider Credentials"
            AchievementsSettingsSection.LOCAL_WINDOWS -> "Achievements · Local Windows"
            AchievementsSettingsSection.UPDATE -> "Achievements · Update Achievements"
            null -> "Shiba Coins"
        },
        onBack   = onBack,
        modifier = modifier,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {

            // Account-wide standing: level, rank, and the running coin wallet. Confirm or tap opens
            // the fullscreen player status view. It is part of the combined legacy screen only;
            // the dedicated Player Card L2 route is handled by the host overlay.
            if (section == null) SettingsFocusable(
                onClick = onOpenPlayerStatus,
                focusKey = "player_card",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            ) { focused ->
                ShibaPlayerCard(
                    wallet = state.wallet,
                    modifier = if (focused) {
                        Modifier.border(2.dp, menuCursorEdge(), RoundedCornerShape(14.dp))
                    } else {
                        Modifier
                    },
                )
            }

            if (section == null || section == AchievementsSettingsSection.PROVIDER_CREDENTIALS) {
            SettingsToggleRow(
                label    = "Enable Shiba Coins",
                sublabel = "Track achievements as Shiba Coins across RetroAchievements and Steam",
                checked  = state.enabled,
                onToggle = { viewModel.setEnabled(it) },
            )

            }

            if (section == null || section == AchievementsSettingsSection.PROVIDER_CREDENTIALS) {
            // ── RetroAchievements ─────────────────────────────────────────────
            SettingsGroup("RetroAchievements")

            if (state.hasRetroAchievements) {
                SettingsValueRow(label = "Connected as", value = state.raUsername)
            }
            Column(modifier = Modifier.alpha(credentialsAlpha)) {
            SettingsTextFieldRow(
                label         = "Username",
                value         = raUsernameDraft,
                onValueChange = { raUsernameDraft = it },
                placeholder   = state.raUsername.ifBlank { "Your RA username" },
                enabled       = credentialsEnabled,
            )
            SettingsTextFieldRow(
                label         = if (state.hasRetroAchievements) "Web API Key (saved)" else "Web API Key",
                value         = raKeyDraft,
                onValueChange = { raKeyDraft = it },
                placeholder   = if (state.hasRetroAchievements) "••••••••  (tap to replace)" else "Paste your RA Web API key",
                isPassword    = true,
                helper        = "retroachievements.org → Settings → Keys → Web API Key",
                enabled       = credentialsEnabled,
            )
            if (credentialsEnabled && raUsernameDraft.isNotBlank() && raKeyDraft.isNotBlank()) {
                SettingsRow(
                    label   = "Connect RetroAchievements",
                    onClick = {
                        viewModel.connectRetroAchievements(raUsernameDraft, raKeyDraft)
                        raUsernameDraft = ""
                        raKeyDraft = ""
                    },
                )
            }
            if (state.hasRetroAchievements) {
                SettingsRow(
                    label    = "Disconnect RetroAchievements",
                    sublabel = "Removes your username and key from this device",
                    onClick  = { viewModel.disconnectRetroAchievements() },
                )
            }
            }

            // ── Steam ──────────────────────────────────────────────────────────
            SettingsGroup("Steam")

            if (state.hasSteam) {
                SettingsValueRow(label = "SteamID64", value = state.steamId64)
            }
            Column(modifier = Modifier.alpha(credentialsAlpha)) {
            SettingsTextFieldRow(
                label         = "SteamID64 or profile name",
                value         = steamIdDraft,
                onValueChange = { steamIdDraft = it },
                placeholder   = state.steamId64.ifBlank { "76561… or your vanity name" },
                helper        = "A vanity name is resolved to a SteamID64 when you connect",
                enabled       = credentialsEnabled,
            )
            SettingsTextFieldRow(
                label         = if (state.hasSteam) "API Key (saved)" else "API Key",
                value         = steamKeyDraft,
                onValueChange = { steamKeyDraft = it },
                placeholder   = if (state.hasSteam) "••••••••  (tap to replace)" else "Paste your Steam Web API key",
                isPassword    = true,
                helper        = "steamcommunity.com/dev — your profile's Game Details must be Public",
                enabled       = credentialsEnabled,
            )
            if (credentialsEnabled && steamIdDraft.isNotBlank() && steamKeyDraft.isNotBlank()) {
                SettingsRow(
                    label   = "Connect Steam",
                    onClick = {
                        viewModel.connectSteam(steamIdDraft, steamKeyDraft)
                        steamIdDraft = ""
                        steamKeyDraft = ""
                    },
                )
            }
            if (state.hasSteam) {
                SettingsRow(
                    label    = "Disconnect Steam",
                    sublabel = "Removes your SteamID and key from this device",
                    onClick  = { viewModel.disconnectSteam() },
                )
            }
            }
            }

            // ── Local Steam (emulated) ─────────────────────────────────────────
            if (section == null || section == AchievementsSettingsSection.LOCAL_WINDOWS) {
            SettingsGroup("Local Steam (Emulated)")
            SettingsToggleRow(
                label    = "Track Local Steam Games (Emulated)",
                sublabel = "Find emulator games, generate their achievement data, and sync unlocks",
                checked  = state.localSteamTrackingEnabled,
                onToggle = { on ->
                    // Enabling is gated behind the save-backup warning; disabling is immediate.
                    if (on) showLocalSteamWarning = true else viewModel.setLocalSteamTracking(false)
                },
            )
            SettingsToggleRow(
                label    = "Install Goldberg & Convert Games",
                sublabel = "When scanning, offer to write achievement data into detected games so " +
                    "the emulator can track them",
                checked  = state.goldbergInstallerEnabled,
                onToggle = { on ->
                    // Converting rewrites game folders, so enabling is gated behind the same
                    // save-backup warning; disabling is immediate.
                    if (on) showGoldbergWarning = true else viewModel.setGoldbergInstaller(false)
                },
            )

            }

            // Update Achievements: the selective update (present, matched games only — never an
            // account-wide import), Auto-match, and, in its own group below them, the confirmed
            // Clear all tracked achievements. Outcomes go to the tray; only live progress and the
            // paused state are shown here.
            if (section == null || section == AchievementsSettingsSection.UPDATE) {
                SettingsGroup("Update")
                SettingsValueRow(label = "Last Updated", value = state.lastSyncedLabel)
                if (state.updatesPaused) {
                    SettingsValueRow(label = "Automatic updates", value = "Updates paused until you resync")
                }
                if (state.isSyncing) {
                    SettingsValueRow(label = "Updating achievements…", value = "${state.syncDone} / ${state.syncTotal}")
                } else {
                    SettingsRow(
                        label = "Update installed achievements",
                        sublabel = when {
                            state.isMatching -> "Runs automatically once auto-match completes"
                            state.updatesPaused -> "Resyncs the games on this device and resumes automatic updates"
                            else -> "Check the games on this device for new achievements"
                        },
                        enabled = !state.isClearing,
                        onClick = { viewModel.updateInstalledAchievements() },
                    )
                }

                SettingsGroup("Auto-match")
                if (state.isMatching) {
                    SettingsValueRow(label = "Matching games…", value = "${state.matchDone} / ${state.matchTotal}")
                } else {
                    SettingsRow(
                        label = "Auto-match games",
                        sublabel = "Link the games on this device to RetroAchievements (ROM hash) and Steam",
                        enabled = !state.isClearing,
                        onClick = { viewModel.autoMatch() },
                    )
                }

                // Kept apart from the update actions so it is never pressed in passing, and never
                // a one-press action: it only opens the confirmation below.
                SettingsGroup("Reset")
                if (state.isClearing) {
                    SettingsValueRow(label = "Clearing achievements…", value = "")
                } else {
                    SettingsRow(
                        label = "Clear all tracked achievements",
                        sublabel = "Removes every achievement recorded in Play Field Portal. Your games " +
                            "and provider connections stay.",
                        focusKey = "achievements_clear_all",
                        onClick = { viewModel.requestClearAll() },
                    )
                }
            }
        }

        if (state.confirmClearVisible) {
            // Cancel holds the default focus, so a stray Confirm press on the controller dismisses.
            val cancelFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { cancelFocus.requestFocus() } }
            AlertDialog(
                onDismissRequest = viewModel::dismissClearAll,
                title = { Text("Clear all tracked achievements?") },
                text = {
                    Text(
                        "This will remove all achievements recorded in Play Field Portal, including " +
                            "earned progress and records for games that are no longer installed. " +
                            "Your games and provider connections will stay. Games will have to be " +
                            "resynced to show their achievements again.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmClearAll) { Text("Clear achievements") }
                },
                dismissButton = {
                    TextButton(
                        onClick = viewModel::dismissClearAll,
                        modifier = Modifier.focusRequester(cancelFocus),
                    ) { Text("Cancel") }
                },
            )
        }

        if (showLocalSteamWarning) {
            AlertDialog(
                onDismissRequest = { showLocalSteamWarning = false },
                title = { Text("Back up your save files first") },
                text = {
                    Text(
                        "Before you sync, open your Windows emulator and back up the save files " +
                            "for any Steam-emulated games you already set up.\n\n" +
                            "Tracking these games lets a sync rewrite each game's emulator config " +
                            "and replace its Steam files so unlocks can be recorded. A game you " +
                            "set up and played before this feature could otherwise lose access to " +
                            "its existing saves.\n\n" +
                            "This also uses your own Steam Web API key to read achievement data — " +
                            "use it at your own risk. Steam tracking is entirely optional; leave " +
                            "this off if you'd rather not accept these risks.\n\n" +
                            "Back up first, then turn this on and Update installed achievements.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.setLocalSteamTracking(true)
                        showLocalSteamWarning = false
                    }) { Text("I've backed up — enable") }
                },
                dismissButton = {
                    TextButton(onClick = { showLocalSteamWarning = false }) { Text("Cancel") }
                },
            )
        }

        if (showGoldbergWarning) {
            AlertDialog(
                onDismissRequest = { showGoldbergWarning = false },
                title = { Text("Back up your save files first") },
                text = {
                    Text(
                        "Before you convert any game, open your Windows emulator and back up the " +
                            "save files for any Steam-emulated games you already set up.\n\n" +
                            "Converting rewrites each game's emulator config and replaces its Steam " +
                            "files so unlocks can be recorded. A game you set up and played before " +
                            "this feature could otherwise lose access to its existing saves.\n\n" +
                            "This also uses your own Steam Web API key to read achievement data — " +
                            "use it at your own risk. Leave this off if you'd rather not accept " +
                            "these risks.\n\n" +
                            "Back up first, then turn this on and scan your games.",
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        viewModel.setGoldbergInstaller(true)
                        showGoldbergWarning = false
                    }) { Text("I've backed up — enable") }
                },
                dismissButton = {
                    TextButton(onClick = { showGoldbergWarning = false }) { Text("Cancel") }
                },
            )
        }
    }
}
