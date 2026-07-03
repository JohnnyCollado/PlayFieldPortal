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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import android.net.Uri
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.core.data.music.MusicIntentResolver
import com.playfieldportal.feature.settings.viewmodel.MusicSettingsViewModel

@Composable
fun MusicSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MusicSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    val rootPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.setRoot(it) } }

    // Open the picker pre-pointed at the saved root (if any) so re-granting after a
    // restore/reinstall lands on the exact same folder in one tap.
    val initialRootUri = state.rootUri?.let { runCatching { Uri.parse(it) }.getOrNull() }

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
            SettingsGroup("Root Folder")

            SettingsValueRow(
                label    = "Root Folder",
                sublabel = "The folder PFP scans for music, including its subfolders",
                value    = state.rootName ?: "Not set",
                focusKey = "music_root",
                onClick  = { rootPicker.launch(initialRootUri) },
            )
            SettingsRow(
                label    = if (state.hasRoot) "Replace Root Folder" else "Add Root Folder",
                sublabel = if (state.hasRoot) "Choose a different folder — replaces the current root"
                           else "Grant one folder; PFP keeps read access and scans it",
                focusKey = "music_root_pick",
                onClick  = { rootPicker.launch(initialRootUri) },
            )
            SettingsRow(
                label    = "Rescan Music Library",
                sublabel = when {
                    state.scanning            -> "Scanning…"
                    state.scanMessage != null -> state.scanMessage
                    else                      -> "Update the library from the root folder"
                },
                onClick  = if (state.scanning || !state.hasRoot) null else ({ viewModel.rescan() }),
            )

            if (state.scanning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp, vertical = 4.dp),
                )
            }

            SettingsGroup("Playback")

            SettingsValueRow(
                label    = "Default Music Player",
                sublabel = "App that opens music tracks",
                value    = state.defaultPlayerLabel,
                focusKey = "music_default_player",
                onClick  = { viewModel.openPlayerPicker() },
            )
        }
    }

    // ── Default player picker: Play Field Portal / System Default / an installed app ──
    if (state.showPlayerPicker) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPlayerPicker() },
            title   = { Text("Default Music Player") },
            text    = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    PlayerChoiceRow(
                        label = "Play Field Portal",
                        selected = state.defaultPlayer == MusicIntentResolver.BUILTIN,
                        onClick = { viewModel.chooseDefaultPlayer(MusicIntentResolver.BUILTIN) },
                    )
                    PlayerChoiceRow(
                        label = "System Default",
                        selected = state.defaultPlayer == null,
                        onClick = { viewModel.chooseDefaultPlayer(null) },
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
                            "No other music players found on this device.",
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
