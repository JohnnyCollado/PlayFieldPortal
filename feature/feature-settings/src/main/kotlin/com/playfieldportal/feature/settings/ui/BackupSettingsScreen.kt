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
import com.playfieldportal.feature.settings.viewmodel.BackupSettingsViewModel

@Composable
fun BackupSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BackupSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.restoreFromUri(it) }
    }

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Backup & Restore",
        onBack   = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsGroup("Backup")

            SettingsValueRow(
                label = "Last Backup",
                value = state.lastBackupDate ?: "Never",
            )

            SettingsRow(
                label    = "Back Up Now",
                sublabel = "Saves library, settings, and play history",
                onClick  = if (state.isWorking) null else ({ viewModel.backupNow() }),
            )

            SettingsRow(
                label    = "Backup Folder",
                sublabel = state.backupFolder,
            )

            if (state.backupFiles.isNotEmpty()) {
                SettingsGroup("Saved Backups")
                state.backupFiles.forEach { name ->
                    SettingsValueRow(label = name, value = "")
                }
            }

            SettingsGroup("What's Included")

            SettingsValueRow(label = "Game Library",        value = "✓")
            SettingsValueRow(label = "Play History",        value = "✓")
            SettingsValueRow(label = "Custom Categories",   value = "✓")
            SettingsValueRow(label = "Settings & API Keys", value = "✓")
            SettingsValueRow(label = "Emulator Profiles",   value = "✓")
            SettingsValueRow(label = "ROM Files",           value = "✗  (not included)")

            SettingsGroup("Restore")

            SettingsRow(
                label    = "Restore from File",
                sublabel = "Browse to a .pfpbackup file",
                onClick  = if (state.isWorking) null else ({
                    restorePicker.launch(arrayOf("*/*"))
                }),
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
}
