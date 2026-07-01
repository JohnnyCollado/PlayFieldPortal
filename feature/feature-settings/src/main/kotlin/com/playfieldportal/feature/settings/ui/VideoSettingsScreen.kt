package com.playfieldportal.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import com.playfieldportal.feature.settings.viewmodel.VideoSettingsViewModel

@Composable
fun VideoSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VideoSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.addLibrary(it) } }

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Video",
        onBack   = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Playback ────────────────────────────────────────────────────────
            SettingsGroup("Playback")

            SettingsValueRow(
                label    = "Default Player",
                sublabel = "Built-in plays in-app. Or pick an external app, or be asked each time.",
                value    = state.defaultPlayerLabel,
                focusKey = "video_default_player",
                onClick  = { viewModel.openPlayerPicker() },
            )

            // ── Libraries ───────────────────────────────────────────────────────
            SettingsGroup("Video Libraries")

            SettingsRow(
                label    = "Add Video Library",
                sublabel = "Pick a folder of videos. PFP keeps read access and scans it on demand.",
                focusKey = "add_video_library",
                onClick  = { folderPicker.launch(null) },
            )

            if (state.libraries.isEmpty()) {
                Text(
                    text     = "No video libraries yet. Add one above to get started.",
                    color    = SettingsSubtext,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
                )
            } else {
                SettingsRow(
                    label    = "Quick Scan All",
                    sublabel = when {
                        state.scanning            -> "Scanning…"
                        state.scanMessage != null -> state.scanMessage
                        else                      -> "Find new/removed files in every enabled library"
                    },
                    onClick  = if (state.scanning) null else ({ viewModel.quickScanAll() }),
                )
                SettingsRow(
                    label    = "Deep Scan All",
                    sublabel = "Full rebuild: refresh metadata and regenerate missing thumbnails",
                    onClick  = if (state.scanning) null else ({ viewModel.deepScanAll() }),
                )
            }

            if (state.scanning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp, vertical = 4.dp),
                )
            }

            // ── Per-library management ──────────────────────────────────────────
            state.libraries.forEach { library ->
                SettingsGroup(library.displayName)

                SettingsValueRow(
                    label    = "${library.videoCount} ${if (library.videoCount == 1) "video" else "videos"}",
                    sublabel = library.lastScannedAt?.let { "Scanned" } ?: "Not scanned yet",
                    value    = if (library.enabled) "On" else "Off",
                    focusKey = "video_enabled_${library.id}",
                    onClick  = { viewModel.scanLibrary(library.id, deep = false) },
                )
                SettingsRow(
                    label   = "Quick Scan",
                    onClick = if (state.scanning) null else ({ viewModel.scanLibrary(library.id, deep = false) }),
                )
                SettingsRow(
                    label   = "Deep Scan",
                    onClick = if (state.scanning) null else ({ viewModel.scanLibrary(library.id, deep = true) }),
                )
                SettingsRow(
                    label   = "Rename",
                    onClick = { viewModel.beginRename(library) },
                )
                SettingsRow(
                    label    = "Remove",
                    sublabel = "Removes the library and its entries from PFP. Files on disk are kept.",
                    onClick  = { viewModel.removeLibrary(library.id) },
                )
            }

            // ── Maintenance ─────────────────────────────────────────────────────
            SettingsGroup("Thumbnails")

            SettingsRow(
                label    = "Clear Thumbnail Cache",
                sublabel = "Delete generated thumbnails. Deep scan regenerates them.",
                onClick  = { viewModel.clearThumbnailCache() },
            )
        }
    }

    // ── Rename dialog ───────────────────────────────────────────────────────────
    state.renameTarget?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.displayName) }
        AlertDialog(
            onDismissRequest = { viewModel.cancelRename() },
            title   = { Text("Rename Library") },
            text    = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = { TextButton(onClick = { viewModel.confirmRename(text) }) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { viewModel.cancelRename() }) { Text("Cancel") } },
        )
    }

    // ── Default player picker ─────────────────────────────────────────────────────
    if (state.showPlayerPicker) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPlayerPicker() },
            title = { Text("Default Player") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    PlayerChoiceRow(
                        label = "Built-in Player (Recommended)",
                        selected = state.defaultPlayer == null || state.defaultPlayer == "builtin",
                        onClick = { viewModel.chooseDefaultPlayer(null) },
                    )
                    PlayerChoiceRow(
                        label = "Ask Every Time",
                        selected = state.defaultPlayer == "ask",
                        onClick = { viewModel.chooseDefaultPlayer("ask") },
                    )
                    state.availablePlayers.forEach { player ->
                        PlayerChoiceRow(
                            label = player.label,
                            selected = state.defaultPlayer == player.packageName,
                            onClick = { viewModel.chooseDefaultPlayer(player.packageName) },
                        )
                    }
                    if (state.availablePlayers.isEmpty()) {
                        Text(
                            "No external video players found on this device.",
                            color = SettingsSubtext,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { viewModel.dismissPlayerPicker() }) { Text("Close") } },
        )
    }
}

@Composable
private fun PlayerChoiceRow(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = (if (selected) "● " else "○ ") + label,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
