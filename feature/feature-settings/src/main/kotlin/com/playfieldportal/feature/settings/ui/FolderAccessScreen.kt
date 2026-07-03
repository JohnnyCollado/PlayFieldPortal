package com.playfieldportal.feature.settings.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.core.data.repository.FolderAccessItem
import com.playfieldportal.core.data.repository.FolderKind
import com.playfieldportal.core.data.repository.FolderLinkStatus
import com.playfieldportal.feature.settings.viewmodel.FolderAccessViewModel

// Standing screen showing each granted SAF folder's link status. After a restore (or any lost
// grant) folders read "Access lost" until re-linked; the ROM Root re-links every console at once.
@Composable
fun FolderAccessScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FolderAccessViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // OpenDocumentTree takes an optional initial URI — we pass the saved folder so the picker opens
    // pre-pointed at it (one-tap re-grant).
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> viewModel.onPickerResult(uri) }

    fun relink(item: FolderAccessItem) {
        val initial = viewModel.beginRelink(item)
        folderPicker.launch(initial)
    }

    fun setRoot() {
        viewModel.beginSetRoot()
        folderPicker.launch(null)
    }

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Folder Access",
        onBack   = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            state.message?.let { msg ->
                SettingsGroup("Done")
                SettingsRow(label = msg, sublabel = "Tap to dismiss", onClick = { viewModel.dismissMessage() })
            }

            SettingsGroup("Folder Access")
            SettingsRow(
                label    = "About re-linking",
                sublabel = "Restoring a backup brings back your folders but not Android's access to " +
                    "them. Re-link each folder here — the picker opens at the saved location, so it's " +
                    "one tap. The ROM Root re-links every console at once.",
            )

            val roms    = state.items.filter { it.kind == FolderKind.ROM_ROOT }
            val media   = state.items.filter { it.kind == FolderKind.MUSIC || it.kind == FolderKind.VIDEO || it.kind == FolderKind.PHOTO }
            val backups = state.items.filter { it.kind == FolderKind.BACKUP }

            SettingsGroup("ROM Roots")
            roms.forEach { FolderRow(it, ::relink) }
            SettingsRow(
                label    = if (roms.isEmpty()) "Add ROM Root" else "Add Another ROM Root",
                sublabel = if (roms.isEmpty())
                    "Grant a root folder (e.g. /Roms); every console scans its subfolder"
                else
                    "Add a second location — e.g. an SD card — for extra storage",
                onClick   = { setRoot() },
            )

            if (media.isNotEmpty()) {
                SettingsGroup("Media Libraries")
                media.forEach { FolderRow(it, ::relink) }
            }

            if (backups.isNotEmpty()) {
                SettingsGroup("Backup")
                backups.forEach { FolderRow(it, ::relink) }
            }

            if (state.items.isEmpty() && !state.loading) {
                SettingsGroup("Folders")
                SettingsRow(
                    label    = "No folders configured yet",
                    sublabel = "Add a ROM Root or music/video/photo folders first, then manage their " +
                        "access here.",
                )
            }
        }
    }
}

@Composable
private fun FolderRow(item: FolderAccessItem, onRelink: (FolderAccessItem) -> Unit) {
    val linked = item.status == FolderLinkStatus.LINKED
    SettingsValueRow(
        label    = item.displayName + kindTag(item.kind),
        value    = if (linked) "Linked" else "Re-link",
        sublabel = if (linked) "Access OK" else "Access lost — tap to re-grant",
        onClick  = { onRelink(item) },
    )
}

private fun kindTag(kind: FolderKind): String = when (kind) {
    FolderKind.ROM_ROOT -> ""
    FolderKind.MUSIC    -> "  (Music)"
    FolderKind.VIDEO    -> "  (Video)"
    FolderKind.PHOTO    -> "  (Photo)"
    FolderKind.BACKUP   -> ""
}
