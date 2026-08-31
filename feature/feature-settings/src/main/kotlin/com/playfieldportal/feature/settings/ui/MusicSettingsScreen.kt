package com.playfieldportal.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.core.data.music.MusicIntentResolver
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpPreview
import com.playfieldportal.feature.settings.viewmodel.MusicSettingsUiState
import com.playfieldportal.feature.settings.viewmodel.MusicSettingsViewModel
import com.playfieldportal.feature.settings.viewmodel.RootFolderRow

@Composable
fun MusicSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MusicSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // Pickers: one for adding a root, one pre-pointed at the root being re-linked (re-granting
    // after a restore/reinstall lands on the exact same folder in one tap).
    val addRootPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.addRoot(it) } }
    var relinkTarget by remember { mutableStateOf<String?>(null) }
    val relinkRootPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val old = relinkTarget
        relinkTarget = null
        if (uri != null && old != null) viewModel.relinkRoot(old, uri)
    }

    MusicSettingsContent(
        state = state,
        onBack = onBack,
        onAddRoot = { addRootPicker.launch(null) },
        onRelinkRoot = { row ->
            relinkTarget = row.treeUri
            relinkRootPicker.launch(runCatching { Uri.parse(row.treeUri) }.getOrNull())
        },
        onRemoveRoot = { viewModel.removeRoot(it.treeUri) },
        onRescan = viewModel::rescan,
        onOpenPlayerPicker = viewModel::openPlayerPicker,
        onDismissPlayerPicker = viewModel::dismissPlayerPicker,
        onChoosePlayer = viewModel::chooseDefaultPlayer,
        onDismissMessage = viewModel::dismissMessage,
        modifier = modifier,
    )
}

@Composable
fun MusicSettingsContent(
    state: MusicSettingsUiState,
    onBack: () -> Unit,
    onAddRoot: () -> Unit,
    onRelinkRoot: (RootFolderRow) -> Unit,
    onRemoveRoot: (RootFolderRow) -> Unit,
    onRescan: () -> Unit,
    onOpenPlayerPicker: () -> Unit,
    onDismissPlayerPicker: () -> Unit,
    onChoosePlayer: (String?) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SettingsScaffold(
        title    = "Settings",
        subtitle = "Music",
        onBack   = onBack,
        modifier = modifier,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            RootAccessSection(
                groupTitle  = "Root Folders",
                roots       = state.roots,
                addLabel    = "Add Music Root",
                addSublabel = "Grant a root folder (e.g. /Music) — add several to span locations",
                onAddRoot   = onAddRoot,
                onRelinkRoot = onRelinkRoot,
                onRemoveRoot = onRemoveRoot,
            )

            SettingsRow(
                label    = "Rescan Music Library",
                sublabel = when {
                    state.scanning            -> "Scanning…"
                    state.scanMessage != null -> state.scanMessage
                    else                      -> "Update the libraries from every root folder"
                },
                focusKey = "music_rescan",
                onClick  = if (state.scanning || !state.hasRoots) null else onRescan,
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
                onClick  = onOpenPlayerPicker,
            )
        }
    }

    // ── Default player picker: Play Field Portal / System Default / an installed app ──
    if (state.showPlayerPicker) {
        AlertDialog(
            onDismissRequest = onDismissPlayerPicker,
            title   = { Text("Default Music Player") },
            text    = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    PlayerChoiceRow(
                        label = "Play Field Portal",
                        selected = state.defaultPlayer == MusicIntentResolver.BUILTIN,
                        onClick = { onChoosePlayer(MusicIntentResolver.BUILTIN) },
                    )
                    PlayerChoiceRow(
                        label = "System Default",
                        selected = state.defaultPlayer == null,
                        onClick = { onChoosePlayer(null) },
                    )
                    state.availablePlayers.forEach { player ->
                        PlayerChoiceRow(
                            label = player.label,
                            selected = state.defaultPlayer == player.packageName,
                            onClick = { onChoosePlayer(player.packageName) },
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
            dismissButton = { TextButton(onClick = onDismissPlayerPicker) { Text("Close") } },
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

@CombinedPreviews
@Composable
private fun MusicSettingsContentPreview() {
    PfpPreview {
        MusicSettingsContent(
            state = MusicSettingsUiState(
                roots = listOf(
                    RootFolderRow("content://preview/tree/primary%3AMusic", "Music", linked = true),
                    RootFolderRow("content://preview/tree/1A2B-3C4D%3AMusic", "SDCARD/Music", linked = false),
                ),
                defaultPlayer = null,
            ),
            onBack = {},
            onAddRoot = {},
            onRelinkRoot = {},
            onRemoveRoot = {},
            onRescan = {},
            onOpenPlayerPicker = {},
            onDismissPlayerPicker = {},
            onChoosePlayer = {},
            onDismissMessage = {},
        )
    }
}
