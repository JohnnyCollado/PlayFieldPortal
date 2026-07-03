package com.playfieldportal.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import android.net.Uri
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.feature.settings.viewmodel.PhotoSettingsViewModel

@Composable
fun PhotoSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PhotoSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // One picker for the single root — picking replaces any existing root and rescans.
    val rootPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.setRoot(it) } }

    // Open the picker pre-pointed at the saved root (if any) so re-granting after a
    // restore/reinstall lands on the exact same folder in one tap.
    val initialRootUri = state.rootUri?.let { runCatching { Uri.parse(it) }.getOrNull() }

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Photo",
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
                sublabel = "The folder PFP scans for photos, including its subfolders",
                value    = state.rootName ?: "Not set",
                focusKey = "photo_root",
                onClick  = { rootPicker.launch(initialRootUri) },
            )
            SettingsRow(
                label    = if (state.hasRoot) "Replace Root Folder" else "Add Root Folder",
                sublabel = if (state.hasRoot) "Choose a different folder — replaces the current root"
                           else "Grant one folder; PFP keeps read access and scans it",
                focusKey = "photo_root_pick",
                onClick  = { rootPicker.launch(initialRootUri) },
            )
            SettingsRow(
                label    = "Rescan Photo Library",
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

            SettingsGroup("Maintenance")

            SettingsRow(
                label    = "Clear Thumbnail Cache",
                sublabel = "Delete generated thumbnails. A rescan regenerates them.",
                onClick  = { viewModel.clearThumbnailCache() },
            )
        }
    }
}
