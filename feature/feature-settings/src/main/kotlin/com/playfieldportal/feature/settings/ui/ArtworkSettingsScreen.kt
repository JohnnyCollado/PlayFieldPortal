package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.feature.settings.viewmodel.ArtworkSettingsViewModel

@Composable
fun ArtworkSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArtworkSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // Artwork Folder & Import lives on its own sub-screen (same pattern as Library Manager's
    // internal sections) — BACK returns here.
    var showImport by remember { mutableStateOf(false) }
    if (showImport) {
        ArtworkImportScreen(onBack = { showImport = false }, modifier = modifier)
        return
    }

    var sgdbKeyDraft by remember(state.apiKeyMasked) { mutableStateOf("") }
    var igdbClientIdDraft by remember(state.igdbClientId) { mutableStateOf("") }
    var igdbClientSecretDraft by remember { mutableStateOf("") }
    var ssUsernameDraft by remember(state.ssUsername) { mutableStateOf("") }
    var ssPasswordDraft by remember { mutableStateOf("") }

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Artwork",
        onBack   = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {

            // ── Artwork library / import ──────────────────────────────────────
            SettingsGroup("Artwork Library")

            SettingsRow(
                label    = if (state.artworkFolderGrantDead) "Artwork Folder & Import  ⚠" else "Artwork Folder & Import",
                sublabel = if (state.artworkFolderGrantDead)
                    "Access to your artwork folder was lost — open to re-link it"
                else "Choose where artwork is stored and import existing artwork from ES-DE",
                onClick  = { showImport = true },
            )

            // ── Artwork status ────────────────────────────────────────────────
            SettingsGroup("Library Artwork Status")

            SettingsValueRow(label = "Total Games",      value = state.status.total.toString())
            SettingsValueRow(label = "Complete",         value = state.status.complete.toString())
            SettingsValueRow(label = "Missing",          value = state.status.missing.toString())
            SettingsValueRow(label = "Stale / Invalid",  value = state.status.stale.toString())
            SettingsRow(
                label    = "Refresh Status",
                sublabel = if (state.isLoadingStatus) "Checking files…" else "Re-check artwork on disk",
                onClick  = if (state.isLoadingStatus) null else ({ viewModel.refreshStatus() }),
            )

            // ── Scraping ──────────────────────────────────────────────────────
            SettingsGroup("Scrape Artwork")

            if (state.isScraping) {
                Column(modifier = Modifier.padding(horizontal = 48.dp, vertical = 10.dp)) {
                    LinearProgressIndicator(
                        progress = {
                            if (state.scrapeTotal > 0) state.scrapeCurrent.toFloat() / state.scrapeTotal else 0f
                        },
                        modifier = Modifier.fillMaxWidth(),
                        color = SettingsAccent,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text  = "${state.scrapeCurrent} / ${state.scrapeTotal}  —  ${state.scrapeTitle}",
                        color = SettingsText,
                    )
                    if (state.scrapeSource.isNotEmpty() || state.scrapeAsset.isNotEmpty()) {
                        Text(
                            text = buildString {
                                if (state.scrapeSource.isNotEmpty()) append(state.scrapeSource)
                                if (state.scrapeSource.isNotEmpty() && state.scrapeAsset.isNotEmpty()) append(" › ")
                                if (state.scrapeAsset.isNotEmpty()) append(state.scrapeAsset)
                            },
                            color = SettingsAccent,
                        )
                    }
                    Text(
                        text  = "${state.scrapeSucceeded} succeeded · ${state.scrapeFailed} failed",
                        color = SettingsSubtext,
                    )
                    Text(
                        text  = "Runs in the background — you can leave this screen.",
                        color = SettingsSubtext,
                    )
                }
                SettingsRow(
                    label    = "Cancel Scrape",
                    sublabel = "Stops after the current game — artwork fetched so far is kept",
                    onClick  = { viewModel.cancelScrape() },
                )
            } else {
                SettingsRow(
                    label    = "Re-Scrape All Games",
                    sublabel = "Clears and re-fetches artwork for every game",
                    onClick  = { viewModel.requestRescrapeAll() },
                )
                SettingsRow(
                    label    = "Scrape Missing Games Only",
                    sublabel = "Fetches only games with missing or invalid artwork — keeps valid art",
                    onClick  = { viewModel.scrapeMissingOnly() },
                )
            }

            state.summary?.let { summary ->
                SettingsRow(
                    label    = summary,
                    sublabel = "Tap to dismiss",
                    onClick  = { viewModel.dismissSummary() },
                )
            }

            // ── Source priority ───────────────────────────────────────────────
            SettingsGroup("Source Priority")

            if (state.ssEnabled) {
                SettingsValueRow(label = "Primary",    value = "ScreenScraper (ROM hash)")
                SettingsValueRow(label = "Fallback 1", value = "TheGamesDB")
                SettingsValueRow(label = "Fallback 2", value = "IGDB")
                SettingsValueRow(label = "Fallback 3", value = "SteamGridDB (artwork)")
            } else {
                SettingsValueRow(label = "Primary",    value = "TheGamesDB")
                SettingsValueRow(label = "Fallback 1", value = "IGDB")
                SettingsValueRow(label = "Fallback 2", value = "SteamGridDB (artwork)")
            }

            // ── Art preferences ───────────────────────────────────────────────
            SettingsGroup("Art Preferences")

            SettingsValueRow(
                label    = "Game Icon Display",
                sublabel = "How game tiles are drawn on the XMB — per-game override in each game's Options (△) menu",
                value    = state.iconDisplayMode.label,
                onClick  = { viewModel.cycleIconDisplayMode() },
            )

            SettingsToggleRow(
                label    = "Animated Icons",
                sublabel = "Play a game's video snap in its icon after resting on it (Custom Icon mode; skipped on low battery)",
                checked  = state.animatedIcons,
                onToggle = { viewModel.setAnimatedIcons(it) },
            )

            SettingsToggleRow(
                label    = "Prefer SteamGridDB Heroes",
                sublabel = "Try SteamGridDB first for hero/banner art",
                checked  = state.preferSteamGridDbHeroes,
                onToggle = { viewModel.setPreferSteamGridDbHeroes(it) },
            )

            SettingsToggleRow(
                label    = "Download Hero Images",
                sublabel = "Wide banner art shown in game detail view",
                checked  = state.downloadHeroes,
                onToggle = { viewModel.setDownloadHeroes(it) },
            )

            SettingsToggleRow(
                label    = "Download Clear Logos",
                sublabel = "Transparent logo PNGs overlaid on hero art",
                checked  = state.downloadLogos,
                onToggle = { viewModel.setDownloadLogos(it) },
            )

            if (state.ssEnabled) {
                SettingsToggleRow(
                    label    = "Download Game Manuals",
                    sublabel = "PDF manuals from ScreenScraper — opens from the game's Options menu",
                    checked  = state.downloadManuals,
                    onToggle = { viewModel.setDownloadManuals(it) },
                )
                SettingsToggleRow(
                    label    = "Download Video Snaps",
                    sublabel = "Short gameplay clips from ScreenScraper — large files",
                    checked  = state.downloadVideoSnaps,
                    onToggle = { viewModel.setDownloadVideoSnaps(it) },
                )
            }

            // ── SteamGridDB API key ───────────────────────────────────────────
            SettingsGroup("SteamGridDB API")

            SettingsTextFieldRow(
                label         = if (state.hasApiKey) "API Key (saved)" else "API Key",
                value         = sgdbKeyDraft,
                onValueChange = { sgdbKeyDraft = it },
                placeholder   = if (state.hasApiKey) "••••••••  (tap to replace)" else "Paste your SteamGridDB key",
                isPassword    = true,
                helper        = "Get a free key at steamgriddb.com/api",
            )

            if (sgdbKeyDraft.isNotBlank()) {
                SettingsRow(
                    label   = "Save API Key",
                    onClick = {
                        viewModel.saveApiKey(sgdbKeyDraft)
                        sgdbKeyDraft = ""
                    },
                )
            }

            if (state.hasApiKey) {
                SettingsRow(
                    label    = "Remove API Key",
                    sublabel = "SteamGridDB artwork will be disabled",
                    onClick  = { viewModel.clearApiKey() },
                )
            }

            // ── IGDB credentials (optional) ───────────────────────────────────
            SettingsGroup("IGDB Credentials (Optional)")

            SettingsTextFieldRow(
                label         = if (state.hasIgdbCredentials) "Client ID (saved)" else "Client ID",
                value         = igdbClientIdDraft,
                onValueChange = { igdbClientIdDraft = it },
                placeholder   = if (state.hasIgdbCredentials) "••••••••" else "Twitch Client ID",
            )
            SettingsTextFieldRow(
                label         = "Client Secret",
                value         = igdbClientSecretDraft,
                onValueChange = { igdbClientSecretDraft = it },
                placeholder   = "Twitch Client Secret",
                isPassword    = true,
                helper        = "Create app at dev.twitch.tv — improves fallback coverage for modern games",
            )

            state.igdbCredentialStatus?.let {
                SettingsRow(label = it, sublabel = "Tap to dismiss", onClick = { viewModel.dismissCredentialStatus() })
            }

            if (igdbClientIdDraft.isNotBlank() && igdbClientSecretDraft.isNotBlank()) {
                SettingsRow(
                    label   = "Test Credentials",
                    onClick = { viewModel.testIgdbCredentials(igdbClientIdDraft, igdbClientSecretDraft) },
                )
                SettingsRow(
                    label   = "Save Credentials",
                    onClick = {
                        viewModel.saveIgdbCredentials(igdbClientIdDraft, igdbClientSecretDraft)
                        igdbClientIdDraft = ""
                        igdbClientSecretDraft = ""
                    },
                )
            }

            if (state.hasIgdbCredentials) {
                SettingsRow(
                    label    = "Clear IGDB Credentials",
                    sublabel = "IGDB will be skipped as a fallback source",
                    onClick  = { viewModel.clearIgdbCredentials() },
                )
            }

            // ── ScreenScraper account (optional) ──────────────────────────────
            // Always visible: credentials can be entered and are stored encrypted even before the
            // build carries dev credentials; scraping activates the moment those exist.
            SettingsGroup("ScreenScraper Account (Optional)")

            if (!state.ssEnabled) {
                SettingsValueRow(
                    label    = "Status",
                    sublabel = "This build has no ScreenScraper developer credentials — hash-based scraping is disabled until they're added",
                    value    = "Inactive",
                )
            }

            SettingsTextFieldRow(
                label         = if (state.hasSsCredentials) "Username (saved: ${state.ssUsername})" else "Username",
                value         = ssUsernameDraft,
                onValueChange = { ssUsernameDraft = it },
                placeholder   = if (state.hasSsCredentials) "Tap to replace" else "ScreenScraper username",
            )
            SettingsTextFieldRow(
                label         = "Password",
                value         = ssPasswordDraft,
                onValueChange = { ssPasswordDraft = it },
                placeholder   = if (state.hasSsCredentials) "••••••••  (tap to replace)" else "ScreenScraper password",
                isPassword    = true,
                helper        = "Free account at screenscraper.fr — raises the scrape rate limit and daily quota. " +
                    "Stored encrypted on this device (Android Keystore).",
            )

            state.ssCredentialStatus?.let {
                SettingsRow(label = it, sublabel = "Tap to dismiss", onClick = { viewModel.dismissSsCredentialStatus() })
            }

            if (ssUsernameDraft.isNotBlank() && ssPasswordDraft.isNotBlank()) {
                if (state.ssEnabled) {
                    SettingsRow(
                        label   = "Test Account",
                        onClick = { viewModel.testSsCredentials(ssUsernameDraft, ssPasswordDraft) },
                    )
                }
                SettingsRow(
                    label   = "Save Account",
                    onClick = {
                        viewModel.saveSsCredentials(ssUsernameDraft, ssPasswordDraft)
                        ssUsernameDraft = ""
                        ssPasswordDraft = ""
                    },
                )
            }

            if (state.hasSsCredentials) {
                SettingsRow(
                    label    = "Clear ScreenScraper Account",
                    sublabel = "Scraping continues at anonymous rate limits",
                    onClick  = { viewModel.clearSsCredentials() },
                )
            }

            // ── Cache ─────────────────────────────────────────────────────────
            SettingsGroup("Cache")

            SettingsValueRow(
                label    = "Stored Artwork Size",
                sublabel = "Image cache + artwork stored on this device (your artwork folder isn't counted)",
                value    = state.diskCacheSizeMb,
            )

            SettingsRow(
                label    = "Clear ScreenScraper URL Cache",
                sublabel = "Forget cached artwork URLs — the next scrape re-asks ScreenScraper per game. Harmless; never deletes artwork",
                onClick  = { viewModel.clearSsUrlCache() },
            )

            SettingsRow(
                label    = "Clear All Artwork",
                sublabel = "Fresh start: removes cached images, stored artwork, and every game's art links. Files in your artwork folder are kept — Relink or re-scrape to restore",
                onClick  = { viewModel.clearCache() },
            )
        }
    }

    if (state.confirmRescrapeAll) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelRescrapeAll() },
            title   = { Text("Re-Scrape All Games?") },
            text    = {
                Text(
                    "This will clear and re-scrape artwork for all ${state.status.total} games. " +
                        "Existing artwork will be replaced. Continue?"
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.confirmRescrapeAll() }) { Text("Re-Scrape All") } },
            dismissButton = { TextButton(onClick = { viewModel.cancelRescrapeAll() }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun credentialFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor   = SettingsAccent,
    unfocusedBorderColor = SettingsDivider,
    focusedTextColor     = SettingsText,
    unfocusedTextColor   = SettingsText,
    cursorColor          = SettingsAccent,
)
