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
import com.playfieldportal.core.ui.preview.PfpScreenPreview
import com.playfieldportal.feature.settings.viewmodel.PhotoSettingsUiState
import com.playfieldportal.feature.settings.viewmodel.PhotoSettingsViewModel
import com.playfieldportal.feature.settings.viewmodel.RootFolderRow

/**
 * Stateful entry point: owns the ViewModel, collects its state, and wires the folder pickers. Kept
 * deliberately thin so the previewable UI lives in [PhotoSettingsContent]. This is the template for
 * previewing any ViewModel-driven screen — see [com.playfieldportal.core.ui.preview.PfpPreview].
 */
@Composable
fun PhotoSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PhotoSettingsViewModel = hiltViewModel(),
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

    PhotoSettingsContent(
        state        = state,
        onBack       = onBack,
        onAddRoot    = { addRootPicker.launch(null) },
        onRelinkRoot = { row ->
            relinkTarget = row.treeUri
            relinkRootPicker.launch(runCatching { Uri.parse(row.treeUri) }.getOrNull())
        },
        onRemoveRoot = { viewModel.removeRoot(it.treeUri) },
        onRescan     = viewModel::rescan,
        onClearCache = viewModel::clearThumbnailCache,
        modifier     = modifier,
    )
}

/**
 * Stateless UI: everything the screen draws, driven purely by [state] and callbacks. No ViewModel,
 * no Hilt — so it renders in `@Preview` with a hand-built [PhotoSettingsUiState].
 */
@Composable
fun PhotoSettingsContent(
    state: PhotoSettingsUiState,
    onBack: () -> Unit,
    onAddRoot: () -> Unit,
    onRelinkRoot: (RootFolderRow) -> Unit,
    onRemoveRoot: (RootFolderRow) -> Unit,
    onRescan: () -> Unit,
    onClearCache: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Removing a root asks first.
    val removeGate = rememberRootRemoveGate()
    val modal = rememberSettingsModal(removeGate.spec)
    SettingsScaffold(
        title    = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.sets_photo_title),
        onBack   = onBack,
        modifier = modifier,
        modalOpen = modal.open,
        onInterceptAction = modal.intercept,
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
                rootKind    = "Photo",
                rootKindRes = R.string.sets_root_kind_photo,
                roots       = state.roots,
                addLabel    = stringResource(R.string.sets_photo_add_root),
                addSublabel = stringResource(R.string.sets_photo_add_root_help),
                onAddRoot   = onAddRoot,
                onRelinkRoot = onRelinkRoot,
                onRemoveRoot = { row -> removeGate.request(row.name) { onRemoveRoot(row) } },
            )

            SettingsRow(
                label    = stringResource(R.string.sets_photo_rescan),
                sublabel = when {
                    state.scanning            -> stringResource(R.string.sets_scanning)
                    state.scanMessage != null -> state.scanMessage.asString()
                    else                      -> stringResource(R.string.sets_rescan_help)
                },
                focusKey = "photo_rescan",
                onClick  = if (state.scanning || !state.hasRoots) null else onRescan,
            )

            if (state.scanning) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp, vertical = 4.dp),
                )
            }

            SettingsGroup(stringResource(R.string.sets_photo_group_maintenance))

            SettingsRow(
                label    = stringResource(R.string.sets_photo_clear_cache),
                sublabel = stringResource(R.string.sets_photo_clear_cache_help),
                onClick  = onClearCache,
            )
        }
    }

    modal.Content()
}

@CombinedPreviews
@Composable
private fun PhotoSettingsContentPreview() {
    PfpScreenPreview {
        PhotoSettingsContent(
            state = PhotoSettingsUiState(
                roots = listOf(
                    RootFolderRow("content://preview/tree/primary%3ADCIM", "DCIM/Camera", linked = true),
                ),
            ),
            onBack       = {},
            onAddRoot    = {},
            onRelinkRoot = {},
            onRemoveRoot = {},
            onRescan     = {},
            onClearCache = {},
        )
    }
}
