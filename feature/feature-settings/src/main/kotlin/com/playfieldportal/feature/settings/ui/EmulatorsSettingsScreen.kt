package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.feature.settings.viewmodel.ADD_CUSTOM_EMULATOR_FOCUS_KEY
import com.playfieldportal.feature.settings.viewmodel.EmulatorsSettingsViewModel
import com.playfieldportal.feature.settings.viewmodel.ProfileListItem

@Composable
fun EmulatorsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmulatorsSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // When the editor is open, swap the entire screen for the editor form
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

    // ── Profile list ──────────────────────────────────────────────────────────
    SettingsScaffold(
        title    = "Settings",
        subtitle = "Emulators",
        onBack   = onBack,
        modifier = modifier,
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
                        onEdit  = if (profile.isCustom) ({ viewModel.openEditor(profile.id) }) else null,
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
        }
    }
}

@Composable
private fun EmulatorProfileRow(
    profile: ProfileListItem,
    onEdit: (() -> Unit)?,
) {
    SettingsRow(
        label    = profile.name,
        sublabel = "${profile.packageName}  ·  ${profile.intentType}",
        focusKey = profile.id,
        trailing = if (onEdit != null) {
            { Text("Edit", color = SettingsAccent) }
        } else null,
        onClick  = onEdit,
    )
}

@Composable
private fun EmulatorHint(text: String) {
    Text(
        text     = text,
        color    = SettingsSubtext,
        modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
    )
}
