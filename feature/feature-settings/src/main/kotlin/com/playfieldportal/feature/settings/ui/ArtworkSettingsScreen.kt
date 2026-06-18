package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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

            SettingsGroup("Fetching")

            SettingsRow(
                label    = "Fetch Missing Artwork",
                sublabel = if (state.isFetching)
                    "Fetching… ${state.fetchProgress}"
                else
                    "Download art for all games without artwork",
                onClick  = if (state.isFetching || !state.hasApiKey) null
                           else ({ viewModel.fetchMissingArtwork() }),
            )

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
}
