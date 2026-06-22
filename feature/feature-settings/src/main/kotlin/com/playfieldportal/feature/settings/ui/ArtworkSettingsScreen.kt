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
import androidx.compose.ui.graphics.Color
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
    var apiKeyDraft by remember(state.apiKeyMasked) { mutableStateOf("") }

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
            SettingsGroup("SteamGridDB API")

            // API key field
            Column(modifier = Modifier.padding(horizontal = 48.dp, vertical = 8.dp)) {
                Text(
                    text  = if (state.hasApiKey) "API Key (saved)" else "API Key",
                    color = SettingsSubtext,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value         = apiKeyDraft,
                    onValueChange = { apiKeyDraft = it },
                    placeholder   = {
                        Text(
                            text  = if (state.hasApiKey) "••••••••  (tap to replace)" else "Paste your SteamGridDB key",
                            color = SettingsSubtext,
                        )
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions      = KeyboardOptions(keyboardType = KeyboardType.Password),
                    singleLine           = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = SettingsAccent,
                        unfocusedBorderColor = SettingsDivider,
                        focusedTextColor     = SettingsText,
                        unfocusedTextColor   = SettingsText,
                        cursorColor          = SettingsAccent,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text  = "Get a free key at steamgriddb.com/api",
                    color = SettingsSubtext.copy(alpha = 0.6f),
                )
            }

            if (apiKeyDraft.isNotBlank()) {
                SettingsRow(
                    label   = "Save API Key",
                    onClick = {
                        viewModel.saveApiKey(apiKeyDraft)
                        apiKeyDraft = ""
                    },
                )
            }

            if (state.hasApiKey) {
                SettingsRow(
                    label    = "Remove API Key",
                    sublabel = "Artwork fetching will be disabled",
                    onClick  = { viewModel.clearApiKey() },
                )
            }

            // ── Artwork status (file-aware) ───────────────────────────────
            SettingsGroup("Library Artwork Status")

            SettingsValueRow(label = "Total Games", value = state.status.total.toString())
            SettingsValueRow(label = "Complete Artwork", value = state.status.complete.toString())
            SettingsValueRow(label = "Missing Artwork", value = state.status.missing.toString())
            SettingsValueRow(label = "Stale / Invalid", value = state.status.stale.toString())
            SettingsRow(
                label    = "Refresh Status",
                sublabel = if (state.isLoadingStatus) "Checking files…" else "Re-check which games have artwork on disk",
                onClick  = if (state.isLoadingStatus) null else ({ viewModel.refreshStatus() }),
            )

            // ── Scraping ──────────────────────────────────────────────────
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
                        text = "${state.scrapeCurrent} / ${state.scrapeTotal}  —  ${state.scrapeTitle}",
                        color = SettingsText,
                    )
                    Text(
                        text = "${state.scrapeSucceeded} succeeded · ${state.scrapeFailed} failed",
                        color = SettingsSubtext,
                    )
                }
            } else {
                SettingsRow(
                    label    = "Re-Scrape All Games",
                    sublabel = if (!state.hasApiKey) "Add a SteamGridDB key first"
                               else "Clears and re-fetches artwork for every game",
                    onClick  = if (!state.hasApiKey) null else ({ viewModel.requestRescrapeAll() }),
                )
                SettingsRow(
                    label    = "Scrape Missing Games Only",
                    sublabel = if (!state.hasApiKey) "Add a SteamGridDB key first"
                               else "Fetches only games with missing or invalid artwork — keeps valid art",
                    onClick  = if (!state.hasApiKey) null else ({ viewModel.scrapeMissingOnly() }),
                )
            }

            state.summary?.let { summary ->
                SettingsRow(
                    label    = summary,
                    sublabel = "Tap to dismiss",
                    onClick  = { viewModel.dismissSummary() },
                )
            }

            SettingsGroup("Cache")

            SettingsValueRow(
                label = "Disk Cache Size",
                value = state.diskCacheSizeMb,
            )

            SettingsRow(
                label    = "Clear Artwork Cache",
                sublabel = "Cached images will be re-downloaded on next view",
                onClick  = { viewModel.clearCache() },
            )

            SettingsGroup("Art Preferences")

            SettingsValueRow(
                label    = "Preferred Grid Style",
                sublabel = "Style used when multiple grids are available",
                value    = state.preferredGridStyle,
                onClick  = { viewModel.cycleGridStyle() },
            )

            SettingsToggleRow(
                label    = "Download Hero Images",
                sublabel = "Wide banner art shown in game detail view",
                checked  = state.downloadHeroes,
                onToggle = { viewModel.setDownloadHeroes(it) },
            )

            SettingsToggleRow(
                label    = "Download Logo Images",
                sublabel = "Transparent logo PNGs overlaid on hero art",
                checked  = state.downloadLogos,
                onToggle = { viewModel.setDownloadLogos(it) },
            )
        }
    }

    if (state.confirmRescrapeAll) {
        AlertDialog(
            onDismissRequest = { viewModel.cancelRescrapeAll() },
            title   = { Text("Re-Scrape All Games?") },
            text    = {
                Text(
                    "This will clear and re-scrape artwork for all ${state.status.total} games in your " +
                        "library. Existing artwork references and downloaded files will be replaced. Continue?"
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.confirmRescrapeAll() }) { Text("Re-Scrape All") } },
            dismissButton = { TextButton(onClick = { viewModel.cancelRescrapeAll() }) { Text("Cancel") } },
        )
    }
}
