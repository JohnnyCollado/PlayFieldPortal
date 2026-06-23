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

    var sgdbKeyDraft by remember(state.apiKeyMasked) { mutableStateOf("") }
    var igdbClientIdDraft by remember(state.igdbClientId) { mutableStateOf("") }
    var igdbClientSecretDraft by remember { mutableStateOf("") }

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
                }
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

            SettingsValueRow(label = "Primary",    value = "TheGamesDB")
            SettingsValueRow(label = "Fallback 1", value = "IGDB")
            SettingsValueRow(label = "Fallback 2", value = "SteamGridDB (artwork)")

            // ── Art preferences ───────────────────────────────────────────────
            SettingsGroup("Art Preferences")

            SettingsValueRow(
                label    = "Preferred Grid Style",
                sublabel = "Style used when multiple grids are available",
                value    = state.preferredGridStyle,
                onClick  = { viewModel.cycleGridStyle() },
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

            // ── SteamGridDB API key ───────────────────────────────────────────
            SettingsGroup("SteamGridDB API")

            Column(modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp)) {
                Text(
                    text  = if (state.hasApiKey) "API Key (saved)" else "API Key",
                    color = SettingsSubtext,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value         = sgdbKeyDraft,
                    onValueChange = { sgdbKeyDraft = it },
                    placeholder   = {
                        Text(
                            text  = if (state.hasApiKey) "••••••••  (tap to replace)" else "Paste your SteamGridDB key",
                            color = SettingsSubtext,
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions      = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine           = true,
                    colors               = credentialFieldColors(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text  = "Get a free key at steamgriddb.com/api",
                    color = SettingsSubtext.copy(alpha = 0.6f),
                )
            }

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

            Column(modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp)) {
                Text(
                    text  = if (state.hasIgdbCredentials) "Client ID (saved)" else "Client ID",
                    color = SettingsSubtext,
                )
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value         = igdbClientIdDraft,
                    onValueChange = { igdbClientIdDraft = it },
                    placeholder   = {
                        Text(
                            text  = if (state.hasIgdbCredentials) "••••••••" else "Twitch Client ID",
                            color = SettingsSubtext,
                        )
                    },
                    singleLine = true,
                    colors     = credentialFieldColors(),
                )
                Spacer(Modifier.height(8.dp))
                Text(text = "Client Secret", color = SettingsSubtext)
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value                = igdbClientSecretDraft,
                    onValueChange        = { igdbClientSecretDraft = it },
                    placeholder          = { Text("Twitch Client Secret", color = SettingsSubtext) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions      = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine           = true,
                    colors               = credentialFieldColors(),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text  = "Create app at dev.twitch.tv — improves fallback coverage for modern games",
                    color = SettingsSubtext.copy(alpha = 0.6f),
                )
            }

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

            // ── Cache ─────────────────────────────────────────────────────────
            SettingsGroup("Cache")

            SettingsValueRow(label = "Disk Cache Size", value = state.diskCacheSizeMb)

            SettingsRow(
                label    = "Clear Artwork Cache",
                sublabel = "Cached images will be re-downloaded on next view",
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
