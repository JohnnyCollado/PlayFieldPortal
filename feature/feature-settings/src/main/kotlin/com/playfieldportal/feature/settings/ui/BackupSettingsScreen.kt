package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.feature.settings.R
import androidx.compose.ui.res.stringResource
import android.content.res.Resources
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
    val resources = rememberSettingsResources()

    // Delete Backup, from the row's menu, asks here first.
    var pendingDelete by remember { mutableStateOf<BackupInfo?>(null) }
    val deleteModal = rememberSettingsModal(
        pendingDelete?.let { info ->
            deleteBackupConfirmSpec(
                resources,
                info,
                onConfirm = { pendingDelete = null; viewModel.deleteBackup(info) },
                onCancel = { pendingDelete = null },
            )
        },
    )
    val restoreModal = rememberSettingsModal(
        pendingRestore?.let { restore ->
            restoreBackupConfirmSpec(
                resources,
                restore.name,
                onConfirm = { pendingRestore = null; viewModel.restoreFromUri(restore.uri) },
                onCancel = { pendingRestore = null },
            )
        },
    )
    val itemMenu = rememberSettingsItemMenu()

    Box(modifier = modifier) {
    SettingsScaffold(
        title    = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.sets_backup_title),
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
            SettingsGroup(stringResource(R.string.sets_backup_group_backup))

            SettingsValueRow(
                label = stringResource(R.string.sets_backup_last_backup),
                value = state.lastBackupDate ?: stringResource(R.string.sets_backup_never),
            )

            SettingsRow(
                label    = stringResource(R.string.sets_backup_folder),
                sublabel = state.backupFolder?.let { stringResource(R.string.sets_backup_folder_saving_to, it) }
                    ?: stringResource(R.string.sets_backup_folder_not_set),
                onClick  = if (state.isWorking) null else ({ folderPicker.launch(null) }),
            )

            SettingsRow(
                label    = stringResource(R.string.sets_backup_now),
                sublabel = if (state.backupFolderSet) stringResource(R.string.sets_backup_now_help)
                    else stringResource(R.string.sets_backup_choose_folder_first),
                onClick  = if (state.isWorking || !state.backupFolderSet) null else ({ viewModel.backupNow() }),
            )

            if (state.backups.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.sets_backup_group_saved))
                state.backups.forEach { info ->
                    SettingsRow(
                        label       = info.name,
                        onLongPress = if (state.isWorking) null else ({
                            itemMenu.show(
                                info.name,
                                savedBackupMenuRows(
                                    resources,
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

            SettingsGroup(stringResource(R.string.sets_backup_group_included))

            listOf(
                R.string.sets_backup_included_library,
                R.string.sets_backup_included_history,
                R.string.sets_backup_included_categories,
                R.string.sets_backup_included_settings,
                R.string.sets_backup_included_profiles,
            ).forEach { included ->
                    SettingsRow(
                        label    = stringResource(included),
                        trailing = { com.playfieldportal.core.ui.components.PfpCheckMark(SettingsText) },
                    )
                }
            SettingsValueRow(
                label = stringResource(R.string.sets_backup_rom_files),
                value = stringResource(R.string.sets_backup_not_included),
            )

            SettingsGroup(stringResource(R.string.sets_backup_group_restore))

            SettingsRow(
                label    = stringResource(R.string.sets_backup_restore_from_file),
                sublabel = stringResource(R.string.sets_backup_restore_from_file_help),
                onClick  = if (state.isWorking) null else ({
                    restorePicker.launch(arrayOf("*/*"))
                }),
            )

            SettingsRow(
                label    = stringResource(R.string.sets_backup_after_restoring),
                sublabel = stringResource(R.string.sets_backup_after_restoring_help),
            )

            if (state.isWorking) {
                SettingsRow(
                    label    = stringResource(R.string.sets_backup_working),
                    sublabel = state.workingMessage.asString(),
                )
            }

            state.errorMessage?.let { err ->
                SettingsRow(
                    label    = stringResource(R.string.sets_backup_error),
                    sublabel = err.asString(),
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
    resources: Resources,
    info: BackupInfo,
    onRestore: (BackupInfo) -> Unit,
    onShare: (BackupInfo) -> Unit,
    onRequestDelete: (BackupInfo) -> Unit,
): List<SettingsMenuItem> = listOf(
    SettingsMenuItem(resources.getString(R.string.sets_backup_menu_restore)) { onRestore(info) },
    SettingsMenuItem(resources.getString(R.string.sets_backup_menu_share)) { onShare(info) },
    SettingsMenuItem(resources.getString(R.string.sets_backup_menu_delete), destructive = true) { onRequestDelete(info) },
)

internal fun deleteBackupConfirmSpec(
    resources: Resources,
    info: BackupInfo,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
): PfpModalSpec.Confirm = PfpModalSpec.Confirm(
    key = "delete_backup_${info.name}",
    title = resources.getString(R.string.sets_backup_delete_title),
    message = resources.getString(R.string.sets_backup_delete_message, info.name),
    confirmLabel = resources.getString(R.string.sets_backup_delete_confirm),
    destructive = true,
    onConfirm = onConfirm,
    onCancel = onCancel,
)

internal fun restoreBackupConfirmSpec(
    resources: Resources,
    name: String?,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
): PfpModalSpec.Confirm = PfpModalSpec.Confirm(
    key = "restore_backup_${name.orEmpty()}",
    title = resources.getString(R.string.sets_backup_restore_title),
    message = if (name != null) {
        resources.getString(R.string.sets_backup_restore_message_named, name)
    } else {
        resources.getString(R.string.sets_backup_restore_message_picked)
    },
    confirmLabel = resources.getString(R.string.sets_backup_restore_confirm),
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
    context.startActivity(Intent.createChooser(send, context.getString(R.string.sets_backup_share_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
