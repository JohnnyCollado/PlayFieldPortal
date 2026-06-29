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
import com.playfieldportal.feature.settings.viewmodel.MusicSettingsViewModel

@Composable
fun MusicSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MusicSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.addFolder(it) } }

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Music",
        onBack   = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Folders ───────────────────────────────────────────────────────
            SettingsGroup("Music Folders")

            SettingsRow(
                label    = "Add Music Folder",
                sublabel = "Pick a folder of audio files. PFP keeps read access and scans it on demand.",
                focusKey = "add_music_folder",
                onClick  = { folderPicker.launch(null) },
            )

            if (state.folders.isEmpty()) {
                Text(
                    text     = "No music folders yet. Add one above to get started.",
                    color    = SettingsSubtext,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
                )
            } else {
                SettingsRow(
                    label    = "Scan All",
                    sublabel = when {
                        state.scanning           -> "Scanning…"
                        state.scanMessage != null -> state.scanMessage
                        else                      -> "Re-scan every enabled folder for music"
                    },
                    onClick  = if (state.scanning) null else ({ viewModel.scanAll() }),
                )
            }

            if (state.scanning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp, vertical = 4.dp),
                )
            }

            // ── Per-folder management ───────────────────────────────────────────
            state.folders.forEach { folder ->
                SettingsGroup(folder.displayName)

                SettingsValueRow(
                    label    = if (folder.enabled) "Enabled" else "Disabled",
                    sublabel = "${folder.trackCount} ${if (folder.trackCount == 1) "track" else "tracks"}" +
                        (folder.lastScannedAt?.let { "  ·  scanned" } ?: "  ·  not scanned yet"),
                    value    = if (folder.enabled) "On" else "Off",
                    focusKey = "music_enabled_${folder.id}",
                    onClick  = { viewModel.setEnabled(folder.id, !folder.enabled) },
                )
                SettingsRow(
                    label   = "Scan This Folder",
                    onClick = if (state.scanning) null else ({ viewModel.scanFolder(folder.id) }),
                )
                SettingsRow(
                    label   = "Rename",
                    onClick = { viewModel.beginRename(folder) },
                )
                SettingsRow(
                    label    = "Remove",
                    sublabel = "Removes the folder and its tracks from PFP. Files on disk are kept.",
                    onClick  = { viewModel.removeFolder(folder.id) },
                )
            }

            // ── Playback ────────────────────────────────────────────────────────
            SettingsGroup("Playback")

            SettingsValueRow(
                label    = "Default Music Player",
                sublabel = "Tracks open in this app so playback continues in the background.",
                value    = state.defaultPlayerLabel,
                focusKey = "music_default_player",
                onClick  = { viewModel.openPlayerPicker() },
            )
        }
    }

    // ── Rename dialog ───────────────────────────────────────────────────────────
    state.renameTarget?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.displayName) }
        AlertDialog(
            onDismissRequest = { viewModel.cancelRename() },
            title   = { Text("Rename Folder") },
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

    // ── Default player picker ────────────────────────────────────────────────────
    if (state.showPlayerPicker) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPlayerPicker() },
            title   = { Text("Default Music Player") },
            text    = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    PlayerChoiceRow(
                        label = "System default",
                        selected = state.defaultPlayerPackage == null,
                        onClick = { viewModel.chooseDefaultPlayer(null) },
                    )
                    state.availablePlayers.forEach { player ->
                        PlayerChoiceRow(
                            label = player.label,
                            selected = state.defaultPlayerPackage == player.packageName,
                            onClick = { viewModel.chooseDefaultPlayer(player.packageName) },
                        )
                    }
                    if (state.availablePlayers.isEmpty()) {
                        Text(
                            "No music players found on this device.",
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
