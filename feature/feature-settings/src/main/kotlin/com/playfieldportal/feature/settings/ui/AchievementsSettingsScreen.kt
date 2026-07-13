package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

            // ── Sync ───────────────────────────────────────────────────────────
            SettingsGroup("Sync")
            SettingsValueRow(label = "Last Synced", value = state.lastSyncedLabel)

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
    }
}
