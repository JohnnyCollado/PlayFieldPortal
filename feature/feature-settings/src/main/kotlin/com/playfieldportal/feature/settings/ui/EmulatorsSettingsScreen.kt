package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.feature.settings.viewmodel.ADD_CUSTOM_EMULATOR_FOCUS_KEY
import com.playfieldportal.feature.settings.viewmodel.EmulatorsSettingsViewModel
import com.playfieldportal.feature.settings.viewmodel.ProfileListItem

private val AutoBadgeColor    = Color(0xFF4A9EFF)
private val UnavailableColor  = Color(0xFFFF6B6B)
private val ModifiedBadgeColor = Color(0xFF45C46A)

@Composable
fun EmulatorsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmulatorsSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    state.editorState?.let { editor ->
        EmulatorProfileEditorScreen(
            editorState           = editor,
            onNameChange          = viewModel::updateEditorName,
            onPackageNameChange   = viewModel::updateEditorPackageName,
            onActivityClassChange = viewModel::updateEditorActivityClass,
            onIntentTypeChange    = viewModel::updateEditorIntentType,
            onPlatformIdsChange   = viewModel::updateEditorPlatformIds,
            onMimeTypeChange      = viewModel::updateEditorMimeType,
            onUseFileUriChange    = viewModel::updateEditorUseFileUri,
            onUseSafUriChange     = viewModel::updateEditorUseSafUri,
            onCustomCommandChange = viewModel::updateEditorCustomCommand,
            onNotesChange         = viewModel::updateEditorNotes,
            onSave                = viewModel::saveEditorProfile,
            onCancel              = viewModel::closeEditor,
            onDelete              = if (!editor.isNew && editor.originalId != null) {
                { viewModel.deleteProfile(editor.originalId) }
            } else null,
            modifier              = modifier,
        )
        return
    }

    SettingsScaffold(
        title           = "Settings",
        subtitle        = "Emulators",
        onBack          = onBack,
        modifier        = modifier,
        restoreFocusKey = state.returnFocusKey,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsGroup("Installed")

            if (state.installedProfiles.isEmpty()) {
                EmulatorHint("No supported emulators detected on this device")
            } else {
                state.installedProfiles.forEach { profile ->
                    EmulatorProfileRow(
                        profile = profile,
                        onEdit  = { viewModel.openEditor(profile.id) },
                    )
                }
            }

            SettingsGroup("Available (Not Installed)")

            if (state.availableProfiles.isEmpty()) {
                EmulatorHint("All bundled profiles are installed")
            } else {
                state.availableProfiles.forEach { profile ->
                    EmulatorProfileRow(profile = profile, onEdit = null)
                }
            }

            SettingsGroup("Custom Profiles")

            if (state.customProfiles.isEmpty()) {
                EmulatorHint("No custom profiles yet")
            } else {
                state.customProfiles.forEach { profile ->
                    EmulatorProfileRow(
                        profile = profile,
                        onEdit  = { viewModel.openEditor(profile.id) },
                    )
                }
            }

            SettingsRow(
                label    = "Add Custom Emulator",
                sublabel = "Define a launch profile for any app",
                focusKey = ADD_CUSTOM_EMULATOR_FOCUS_KEY,
                onClick  = { viewModel.openEditor(null) },
            )

            SettingsGroup("Maintenance")

            SettingsRow(
                label    = "Reset Emulator Configuration",
                sublabel = "Clear launch settings and restore defaults. Your games, artwork, and saves are not affected.",
                focusKey = "reset_emulator_config",
                onClick  = { viewModel.requestResetEmulatorConfig() },
            )
        }
    }

    if (state.showResetConfirm) {
        AlertDialog(
            onDismissRequest = viewModel::cancelResetEmulatorConfig,
            title = { Text("Reset Emulator Configuration?") },
            text  = {
                Text(
                    "This will clear all auto-detected and custom emulator launch settings, " +
                    "then restore bundled defaults.\n\n" +
                    "Your game library, ROM paths, artwork, metadata, and save data are not affected."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmResetEmulatorConfig) {
                    Text("Reset", color = UnavailableColor)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelResetEmulatorConfig) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun EmulatorProfileRow(
    profile: ProfileListItem,
    onEdit: (() -> Unit)?,
) {
    SettingsRow(
        label    = profile.name,
        sublabel = buildSublabel(profile),
        focusKey = profile.id,
        trailing = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!profile.isAvailable) {
                    Badge("Unavailable", UnavailableColor)
                }
                if (profile.isAutoGenerated && !profile.userModified) {
                    Badge("Auto", AutoBadgeColor)
                }
                if (profile.userModified) {
                    Badge("Edited", ModifiedBadgeColor)
                }
                if (onEdit != null) {
                    Text("Edit", color = SettingsAccent)
                }
            }
        },
        onClick = onEdit,
    )
}

@Composable
private fun Badge(label: String, color: Color) {
    Text(
        text     = label,
        color    = color,
        fontSize = 10.sp,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

private fun buildSublabel(profile: ProfileListItem): String {
    val base = "${profile.packageName}  ·  ${profile.intentType}"
    return when {
        profile.autoSource == "retroarch-core" -> "$base  ·  RetroArch core"
        profile.isAutoGenerated                -> "$base  ·  auto-detected"
        else                                   -> base
    }
}

@Composable
private fun EmulatorHint(text: String) {
    Text(
        text     = text,
        color    = SettingsSubtext,
        modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
    )
}
