package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.core.ui.achievement.ShibaPlayerCard
import com.playfieldportal.feature.settings.viewmodel.AchievementsSettingsViewModel

@Composable
fun AchievementsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AchievementsSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    var raUsernameDraft by remember(state.raUsername) { mutableStateOf("") }
    var raKeyDraft by remember(state.hasRetroAchievements) { mutableStateOf("") }
    var steamIdDraft by remember(state.steamId64) { mutableStateOf("") }
    var steamKeyDraft by remember(state.hasSteam) { mutableStateOf("") }
    var showLocalSteamWarning by remember { mutableStateOf(false) }

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Shiba Coins",
        onBack   = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {

            // Account-wide standing: level, rank, and the running coin wallet.
            ShibaPlayerCard(
                wallet = state.wallet,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )

            SettingsToggleRow(
                label    = "Enable Shiba Coins",
                sublabel = "Track achievements as Shiba Coins across RetroAchievements and Steam",
                checked  = state.enabled,
                onToggle = { viewModel.setEnabled(it) },
            )

            // ── RetroAchievements ─────────────────────────────────────────────
            SettingsGroup("RetroAchievements")

            if (state.hasRetroAchievements) {
                SettingsValueRow(label = "Connected as", value = state.raUsername)
            }
            SettingsTextFieldRow(
                label         = "Username",
                value         = raUsernameDraft,
                onValueChange = { raUsernameDraft = it },
                placeholder   = state.raUsername.ifBlank { "Your RA username" },
            )
            SettingsTextFieldRow(
                label         = if (state.hasRetroAchievements) "Web API Key (saved)" else "Web API Key",
                value         = raKeyDraft,
                onValueChange = { raKeyDraft = it },
                placeholder   = if (state.hasRetroAchievements) "••••••••  (tap to replace)" else "Paste your RA Web API key",
                isPassword    = true,
                helper        = "retroachievements.org → Settings → Keys → Web API Key",
            )
            if (raUsernameDraft.isNotBlank() && raKeyDraft.isNotBlank()) {
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

            // ── Steam ──────────────────────────────────────────────────────────
            SettingsGroup("Steam")

            if (state.hasSteam) {
                SettingsValueRow(label = "SteamID64", value = state.steamId64)
            }
            SettingsTextFieldRow(
                label         = "SteamID64 or profile name",
                value         = steamIdDraft,
                onValueChange = { steamIdDraft = it },
                placeholder   = state.steamId64.ifBlank { "76561… or your vanity name" },
                helper        = "A vanity name is resolved to a SteamID64 when you connect",
            )
            SettingsTextFieldRow(
                label         = if (state.hasSteam) "API Key (saved)" else "API Key",
                value         = steamKeyDraft,
                onValueChange = { steamKeyDraft = it },
                placeholder   = if (state.hasSteam) "••••••••  (tap to replace)" else "Paste your Steam Web API key",
                isPassword    = true,
                helper        = "steamcommunity.com/dev — your profile's Game Details must be Public",
            )
            if (steamIdDraft.isNotBlank() && steamKeyDraft.isNotBlank()) {
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

            // ── Local Steam (emulated) ─────────────────────────────────────────
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

            // ── Sync ───────────────────────────────────────────────────────────
            SettingsGroup("Sync")
            SettingsValueRow(label = "Last Synced", value = state.lastSyncedLabel)
            if (state.isSyncing) {
                SettingsValueRow(label = "Syncing coins…", value = "${state.syncDone} / ${state.syncTotal}")
            } else {
                SettingsRow(
                    label = "Sync all coins",
                    sublabel = if (state.isMatching) "Runs automatically once auto-match completes"
                               else "Refresh earned coins for every linked game",
                    onClick = { viewModel.syncAll() },
                )
            }
            state.syncResult?.let { r ->
                val summary = buildString {
                    append("${r.synced} synced")
                    if (r.noCoins > 0) append(" · ${r.noCoins} no coins")
                    if (r.failed > 0) append(" · ${r.failed} failed")
                }
                SettingsRow(
                    label = summary,
                    sublabel = if (r.missingCredentials) "Some providers need credentials — tap to dismiss"
                               else "Tap to dismiss",
                    onClick = { viewModel.dismissSyncResult() },
                )
            }

            // ── Account import ─────────────────────────────────────────────────
            if (state.hasRetroAchievements) {
                SettingsGroup("Account import")
                if (state.isImporting) {
                    SettingsValueRow(label = "Importing RA history…", value = "${state.importDone} / ${state.importTotal}")
                } else {
                    SettingsRow(
                        label = "Import my RA history",
                        sublabel = "Track every game your RetroAchievements account has progress in — even without a local copy",
                        onClick = { viewModel.importRaHistory() },
                    )
                }
                state.importResult?.let { r ->
                    val summary = buildString {
                        append("${r.imported} imported")
                        if (r.noCoins > 0) append(" · ${r.noCoins} no coins")
                        if (r.failed > 0) append(" · ${r.failed} failed")
                    }
                    SettingsRow(
                        label = summary,
                        sublabel = if (r.missingCredentials) "RetroAchievements needs credentials — tap to dismiss"
                                   else "Tap to dismiss",
                        onClick = { viewModel.dismissImportResult() },
                    )
                }
            }
            if (state.hasSteam) {
                if (!state.hasRetroAchievements) SettingsGroup("Account import")
                if (state.isSteamImporting) {
                    SettingsRow(
                        label = "Importing Steam library…  ${state.steamImportDone} / ${state.steamImportTotal}",
                        sublabel = "Runs in the background — safe to leave this screen. Tap to cancel.",
                        onClick = { viewModel.cancelSteamImport() },
                    )
                } else {
                    SettingsRow(
                        label = "Import my Steam library",
                        sublabel = "Track every owned game with achievement progress — a large library takes a while, and runs in the background",
                        onClick = { viewModel.importSteamLibrary() },
                    )
                }
                state.steamImportSummary?.let { summary ->
                    SettingsRow(
                        label = summary,
                        sublabel = "Tap to dismiss",
                        onClick = { viewModel.dismissSteamImportSummary() },
                    )
                }
            }

            // ── Auto-match ─────────────────────────────────────────────────────
            SettingsGroup("Auto-match")
            if (state.isMatching) {
                SettingsValueRow(label = "Matching games…", value = "${state.matchDone} / ${state.matchTotal}")
            } else {
                SettingsRow(
                    label = "Auto-match games",
                    sublabel = "Link RetroAchievements (ROM hash) and Steam (title) automatically",
                    onClick = { viewModel.autoMatch() },
                )
            }
            state.matchReport?.let { report ->
                SettingsValueRow(label = "Matched", value = report.matched.toString())
                if (report.unmatched.isEmpty()) {
                    SettingsValueRow(label = "Unmatched", value = "0")
                } else {
                    SettingsRow(
                        label = "Unmatched: ${report.unmatched.size} (tap to dismiss)",
                        sublabel = "Link these from each game's Shiba Coins screen",
                        onClick = { viewModel.dismissReport() },
                    )
                    report.unmatched.forEach { u ->
                        SettingsValueRow(label = u.title, sublabel = u.platformId, value = u.reason)
                    }
                }
            }

            state.message?.let {
                SettingsRow(label = it, sublabel = "Tap to dismiss", onClick = { viewModel.dismissMessage() })
            }
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
                            "Back up first, then turn this on and Sync All.",
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
    }
}
