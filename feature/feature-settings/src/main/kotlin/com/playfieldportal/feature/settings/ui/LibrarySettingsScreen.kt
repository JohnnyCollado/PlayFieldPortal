package com.playfieldportal.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.feature.settings.viewmodel.LibrarySettingsViewModel

@Composable
fun LibrarySettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibrarySettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // Root directory picker — sets up the ROM library root
    val rootPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.setupRoot(it) } }

    // Extra folder picker — adds an additional scan source (no platform lock)
    val extraFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.addExtraFolder(it) } }

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Library",
        onBack   = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {

            // ── ROM Library Root ──────────────────────────────────────────
            SettingsGroup("ROM Library Root")

            if (state.rootPath == null) {
                SettingsRow(
                    label    = "Choose Root Folder",
                    sublabel = "Pick the folder where you store your ROMs. Platform subfolders (psx/, psp/, etc.) will be read automatically.",
                    onClick  = { rootPicker.launch(null) },
                )
            } else {
                SettingsValueRow(
                    label    = "Root Folder",
                    sublabel = "All platform subfolders inside here are scanned",
                    value    = state.rootPath!!.substringAfterLast('/'),
                )
                SettingsRow(
                    label   = "Change Root Folder",
                    onClick = { rootPicker.launch(null) },
                )
            }

            // ── ROM Sources ───────────────────────────────────────────────
            SettingsGroup("ROM Sources")

            if (state.sources.isEmpty()) {
                Text(
                    text     = "No scan folders added yet. Choose a root folder above to get started.",
                    color    = SettingsSubtext,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
                )
            } else {
                state.sources.forEach { source ->
                    val badge = when {
                        source.platformId != null -> source.platformId.uppercase()
                        else -> null
                    }
                    SettingsRow(
                        label    = source.label.ifBlank { source.path.substringAfterLast('/') },
                        sublabel = buildString {
                            append(source.path)
                            if (badge != null) append("  ·  Platform: $badge")
                            append("  ·  ${source.gameCount} games")
                        },
                        trailing = {
                            Text(
                                text     = "Remove",
                                color    = SettingsAccent,
                                modifier = Modifier
                                    .padding(start = 8.dp)
                                    .clickable { viewModel.removeSource(source.id) },
                            )
                        },
                    )
                }
            }

            SettingsRow(
                label    = "Add Extra Folder",
                sublabel = "Add an additional folder (e.g. a second drive or existing ROM collection)",
                onClick  = { extraFolderPicker.launch(null) },
            )

            // ── Scanning ──────────────────────────────────────────────────
            SettingsGroup("Scanning")

            if (state.isScanning) {
                state.scanProgress?.let { progress ->
                    LinearProgressIndicator(
                        progress = { progress.filesScanned.toFloat() / progress.totalEstimated.coerceAtLeast(1) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 48.dp, vertical = 4.dp),
                    )
                    Text(
                        text     = "${progress.filesFound} found  ·  ${progress.filesScanned} scanned",
                        color    = SettingsSubtext,
                        modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
                    )
                } ?: LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp, vertical = 4.dp),
                )
            }

            SettingsRow(
                label    = "Scan Now",
                sublabel = when {
                    state.isScanning   -> "Scan in progress…"
                    state.scanMessage != null -> state.scanMessage
                    else               -> "Search all sources for new ROMs"
                },
                onClick  = if (state.isScanning || state.sources.isEmpty()) null
                           else ({ viewModel.scanNow() }),
            )

            SettingsValueRow(
                label = "Last Scan",
                value = state.lastScanTime ?: "Never",
            )

            // ── Advanced ──────────────────────────────────────────────────
            SettingsGroup("Advanced")

            SettingsToggleRow(
                label    = "Show Unmatched ROMs",
                sublabel = "Flag files that couldn't be matched to a platform",
                checked  = state.showUnmatched,
                onToggle = { viewModel.setShowUnmatched(it) },
            )

            SettingsRow(
                label    = "Clear Library",
                sublabel = "Remove all games from the database. ROMs on disk are not deleted.",
                onClick  = { viewModel.clearLibrary() },
            )
        }
    }
}
