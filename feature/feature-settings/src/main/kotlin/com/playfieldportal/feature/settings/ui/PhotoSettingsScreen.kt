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
import com.playfieldportal.feature.settings.viewmodel.PhotoSettingsViewModel

@Composable
fun PhotoSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PhotoSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    val addFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.addLibrary(it) } }

    // Separate launcher for repointing an existing library, so a cancelled pick never adds one.
    val changeFolderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) viewModel.changeFolder(uri) else viewModel.cancelChangeFolder() }

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
            // ── Libraries ───────────────────────────────────────────────────────
            SettingsGroup("Photo Libraries")

            SettingsRow(
                label    = "Add Photo Library",
                sublabel = "Pick a folder of photos. PFP keeps read access and scans it on demand.",
                focusKey = "add_photo_library",
                onClick  = { addFolderPicker.launch(null) },
            )

            if (state.libraries.isEmpty()) {
                Text(
                    text     = "No photo libraries yet. Add one above to get started.",
                    color    = SettingsSubtext,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
                )
            } else {
                SettingsRow(
                    label    = "Quick Scan All",
                    sublabel = when {
                        state.scanning            -> "Scanning…"
                        state.scanMessage != null -> state.scanMessage
                        else                      -> "Find new/removed photos in every library"
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
                    label    = "${library.photoCount} ${if (library.photoCount == 1) "photo" else "photos"}",
                    sublabel = library.lastScannedAt?.let { "Scanned" } ?: "Not scanned yet",
                    value    = if (library.enabled) "On" else "Off",
                    focusKey = "photo_enabled_${library.id}",
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
                SettingsValueRow(
                    label    = "Include Subfolders",
                    sublabel = "Off scans only this folder's own photos; On also walks nested folders",
                    value    = if (library.scanRecursively) "On" else "Off",
                    focusKey = "photo_recursive_${library.id}",
                    onClick  = if (state.scanning) null else ({ viewModel.toggleRecursive(library) }),
                )
                SettingsRow(
                    label   = "Rename",
                    onClick = { viewModel.beginRename(library) },
                )
                SettingsRow(
                    label    = "Change Folder",
                    sublabel = "Point this album at a different folder and rescan",
                    onClick  = if (state.scanning) null else ({
                        viewModel.beginChangeFolder(library.id)
                        changeFolderPicker.launch(null)
                    }),
                )
                SettingsRow(
                    label    = "Remove",
                    sublabel = "Removes the album and its entries from PFP. Photos on disk are kept.",
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
            title   = { Text("Rename Album") },
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
}
