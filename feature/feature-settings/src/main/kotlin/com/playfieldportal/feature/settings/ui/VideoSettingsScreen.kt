package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.feature.settings.R
import androidx.compose.ui.res.stringResource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpPreview
import com.playfieldportal.feature.settings.viewmodel.RootFolderRow
import com.playfieldportal.feature.settings.viewmodel.VideoSettingsUiState
import com.playfieldportal.feature.settings.viewmodel.VideoSettingsViewModel

@Composable
fun VideoSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VideoSettingsViewModel = hiltViewModel(),
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

    VideoSettingsContent(
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
fun VideoSettingsContent(
    state: VideoSettingsUiState,
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
    // The default-player picker, as the shared Choice modal so a pad can drive it (survey P6).
    // Removing a root asks first; the question takes the screen's one modal slot while it is up.
    val removeGate = rememberRootRemoveGate()
    val playerModal = rememberSettingsModal(
        removeGate.spec ?: if (!state.showPlayerPicker) null else playerPickerSpec(
            title = stringResource(R.string.sets_video_player_title),
            options = listOf(
                PlayerOption("builtin", stringResource(R.string.sets_player_builtin)),
                PlayerOption("ask", stringResource(R.string.sets_player_system_default)),
            ) + state.availablePlayers.map { PlayerOption(it.packageName, it.label) },
            current = state.defaultPlayer ?: "builtin",
            emptyNote = if (state.availablePlayers.isEmpty()) stringResource(R.string.sets_video_player_none) else null,
            onChoose = onChoosePlayer,
            onClose = onDismissPlayerPicker,
        ),
    )
    SettingsScaffold(
        title    = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.sets_video_title),
        onBack   = onBack,
        modifier = modifier,
        modalOpen = playerModal.open,
        onInterceptAction = playerModal.intercept,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            RootAccessSection(
                groupTitle  = stringResource(R.string.sets_roots_group),
                rootKind    = "Video",
                rootKindRes = R.string.sets_root_kind_video,
                roots       = state.roots,
                addLabel    = stringResource(R.string.sets_video_add_root),
                addSublabel = stringResource(R.string.sets_video_add_root_help),
                onAddRoot   = onAddRoot,
                onRelinkRoot = onRelinkRoot,
                onRemoveRoot = { row -> removeGate.request(row.name) { onRemoveRoot(row) } },
            )

            SettingsRow(
                label    = stringResource(R.string.sets_video_rescan),
                sublabel = when {
                    state.scanning            -> stringResource(R.string.sets_scanning)
                    state.scanMessage != null -> state.scanMessage.asString()
                    else                      -> stringResource(R.string.sets_rescan_help)
                },
                focusKey = "video_rescan",
                onClick  = if (state.scanning || !state.hasRoots) null else onRescan,
            )

            if (state.scanning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp, vertical = 4.dp),
                )
            }

            SettingsGroup(stringResource(R.string.sets_playback_group))

            SettingsValueRow(
                label    = stringResource(R.string.sets_video_player),
                sublabel = stringResource(R.string.sets_video_player_help),
                value    = state.defaultPlayerLabel.asString(),
                focusKey = "video_default_player",
                onClick  = onOpenPlayerPicker,
            )
        }
    }

    playerModal.Content()
}

@CombinedPreviews
@Composable
private fun VideoSettingsContentPreview() {
    PfpPreview {
        VideoSettingsContent(
            state = VideoSettingsUiState(
                roots = listOf(
                    RootFolderRow("content://preview/tree/primary%3AMovies", "Movies", linked = true),
                ),
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
