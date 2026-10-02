package com.playfieldportal.feature.settings.ui

import android.content.Context
import android.content.Intent
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Box
import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.feature.backup.BackupInfo
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.playfieldportal.feature.settings.viewmodel.BackupSettingsViewModel

@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // A restore replaces the current data, so both ways in (a saved backup's menu, a picked file)
    // ask here first. [name] is null for a picked file, whose document name is not known.
    var pendingRestore by remember { mutableStateOf<PendingRestore?>(null) }

    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { pendingRestore = PendingRestore(it, name = null) }
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? -> uri?.let { viewModel.setBackupFolder(it) } }

    val context = LocalContext.current

    // Delete Backup, from the row's menu, asks here first.
    var pendingDelete by remember { mutableStateOf<BackupInfo?>(null) }
    val deleteModal = rememberSettingsModal(
        pendingDelete?.let { info ->
            deleteBackupConfirmSpec(
                info,
                onConfirm = { pendingDelete = null; viewModel.deleteBackup(info) },
                onCancel = { pendingDelete = null },
            )
        },
    )
    val restoreModal = rememberSettingsModal(
        pendingRestore?.let { restore ->
            restoreBackupConfirmSpec(
                restore.name,
                onConfirm = { pendingRestore = null; viewModel.restoreFromUri(restore.uri) },
                onCancel = { pendingRestore = null },
            )
        },
    )
    val itemMenu = rememberSettingsItemMenu()

    Box(modifier = modifier) {
    SettingsScaffold(
        title    = "Settings",
        subtitle = "Backup & Restore",
        onBack   = onBack,
        modifier = Modifier.fillMaxSize(),
        modalOpen = deleteModal.open || restoreModal.open,
        // A modal is a hard input boundary: nothing behind it sees a press.
        onInterceptAction = { deleteModal.intercept(it) || restoreModal.intercept(it) || itemMenu.intercept(it) },
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            SettingsGroup("Backup")

            SettingsValueRow(
                label = "Last Backup",
                value = state.lastBackupDate ?: "Never",
            )

            SettingsRow(
                label    = "Backup Folder",
                sublabel = state.backupFolder?.let { "Saving to: $it  (tap to change)" }
                    ?: "Not set — tap to choose where backups are saved",
                onClick  = if (state.isWorking) null else ({ folderPicker.launch(null) }),
            )

            SettingsRow(
                label    = "Back Up Now",
                sublabel = if (state.backupFolderSet) "Saves library, settings, and play history"
                    else "Choose a backup folder first",
                onClick  = if (state.isWorking || !state.backupFolderSet) null else ({ viewModel.backupNow() }),
            )

            if (state.backups.isNotEmpty()) {
                SettingsGroup("Saved Backups")
                state.backups.forEach { info ->
                    SettingsRow(
                        label       = info.name,
                        onLongPress = if (state.isWorking) null else ({
                            itemMenu.show(
                                info.name,
                                savedBackupMenuRows(
                                    info,
                                    onRestore = { pendingRestore = PendingRestore(it.uri, it.name) },
                                    onShare = { shareBackup(context, it) },
                                    onRequestDelete = { pendingDelete = it },
                                ),
                            )
                        }),
                    )
                }
            }

            SettingsGroup("What's Included")

            listOf("Game Library", "Play History", "Custom Categories", "Settings & API Keys", "Emulator Profiles")
                .forEach { included ->
                    SettingsRow(
                        label    = included,
                        trailing = { com.playfieldportal.core.ui.components.PfpCheckMark(SettingsText) },
                    )
                }
            SettingsValueRow(label = "ROM Files",           value = "✗  (not included)")

            SettingsGroup("Restore")

            SettingsRow(
                label    = "Restore from File",
                sublabel = "Browse to a .pfpbackup file",
                onClick  = if (state.isWorking) null else ({
                    restorePicker.launch(arrayOf("*/*"))
                }),
            )

            SettingsRow(
                label    = "After Restoring",
                sublabel = "Your folders come back, but Android's access to them does not. Re-link " +
                    "each root under its section's Root Access (Library, Music, Video, Photo) — one " +
                    "tap each; re-linking a root restores everything under it at once.",
            )

            if (state.isWorking) {
                SettingsRow(
                    label    = "Working…",
                    sublabel = state.workingMessage,
                )
            }

            state.errorMessage?.let { err ->
                SettingsRow(
                    label    = "Error",
                    sublabel = err,
                    onClick  = { viewModel.dismissError() },
                )
            }
        }
    }
    itemMenu.Content()
    deleteModal.Content()
    restoreModal.Content()
    }
}

private class PendingRestore(val uri: Uri, val name: String?)

/** The Saved Backups row menu. Delete Backup is red and last, and only asks: the delete waits for a confirm. */
internal fun savedBackupMenuRows(
    info: BackupInfo,
    onRestore: (BackupInfo) -> Unit,
    onShare: (BackupInfo) -> Unit,
    onRequestDelete: (BackupInfo) -> Unit,
): List<SettingsMenuItem> = listOf(
    SettingsMenuItem("Restore") { onRestore(info) },
    SettingsMenuItem("Share") { onShare(info) },
    SettingsMenuItem("Delete Backup", destructive = true) { onRequestDelete(info) },
)

internal fun deleteBackupConfirmSpec(
    info: BackupInfo,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
): PfpModalSpec.Confirm = PfpModalSpec.Confirm(
    key = "delete_backup_${info.name}",
    title = "Delete Backup?",
    message = "\"${info.name}\" will be deleted. This can't be undone.",
    confirmLabel = "Delete",
    destructive = true,
    onConfirm = onConfirm,
    onCancel = onCancel,
)

internal fun restoreBackupConfirmSpec(
    name: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
): PfpModalSpec.Confirm = PfpModalSpec.Confirm(
    key = "restore_backup_${name.orEmpty()}",
    title = "Restore Backup?",
    message = (name?.let { "\"$it\"" } ?: "This backup") +
        " will replace your current library, settings and play history. This can't be undone.",
    confirmLabel = "Restore",
    destructive = true,
    onConfirm = onConfirm,
    onCancel = onCancel,
)

// Hands the backup document to the system share sheet; the chooser gets a read grant on its uri.
private fun shareBackup(context: Context, info: BackupInfo) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, info.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share backup").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
