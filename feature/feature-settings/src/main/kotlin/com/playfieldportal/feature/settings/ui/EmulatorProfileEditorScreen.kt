package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.domain.model.IntentType
import com.playfieldportal.feature.settings.viewmodel.EmulatorTemplate
import com.playfieldportal.feature.settings.viewmodel.ProfileEditorState

private val EditorText     = Color(0xFFFFFFFF)
private val EditorSubtext  = Color(0xFF888888)
private val EditorAccent   = Color(0xFF4A90D9)
private val EditorError    = Color(0xFFE57373)
private val EditorBorder   = Color(0xFF444444)

@Composable
fun EmulatorProfileEditorScreen(
    editorState: ProfileEditorState,
    onNameChange: (String) -> Unit,
    onPackageNameChange: (String) -> Unit,
    onActivityClassChange: (String) -> Unit,
    onIntentTypeChange: (IntentType) -> Unit,
    onPlatformIdsChange: (String) -> Unit,
    onMimeTypeChange: (String) -> Unit,
    onUseFileUriChange: (Boolean) -> Unit,
    onUseSafUriChange: (Boolean) -> Unit,
    onCustomCommandChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onIntentActionChange: (String) -> Unit,
    onIntentExtrasChange: (String) -> Unit,
    onIntentFlagsChange: (String) -> Unit,
    onIntentCategoryChange: (String) -> Unit,
    onCoreChange: (String) -> Unit,
    onExtensionsChange: (String) -> Unit,
    onApplyTemplate: (EmulatorTemplate) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onDelete: (() -> Unit)?,
    onTestLaunch: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val subtitle = if (editorState.isNew) "New Profile" else "Edit Profile"

    SettingsScaffold(
        title    = "Emulators",
        subtitle = subtitle,
        onBack   = onCancel,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {

            // ── Detection banner (wizard) ─────────────────────────────────
            editorState.detectionNote?.let { note ->
                Text(
                    text     = note,
                    color    = EditorAccent,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 12.dp),
                )
            }

            // ── Recommended templates ─────────────────────────────────────
            SettingsGroup("Recommended Templates")
            EmulatorTemplate.entries.forEach { template ->
                SettingsRow(
                    label    = template.label,
                    sublabel = template.description,
                    onClick  = { onApplyTemplate(template) },
                )
            }

            // ── Required fields ───────────────────────────────────────────
            SettingsGroup("Required")

            EditorTextField(
                label       = "Display Name",
                value       = editorState.name,
                onValueChange = onNameChange,
                placeholder = "e.g. My Emulator",
            )

            if (editorState.intentType != IntentType.CUSTOM_COMMAND) {
                EditorTextField(
                    label         = "Package Name",
                    value         = editorState.packageName,
                    onValueChange = onPackageNameChange,
                    placeholder   = "e.g. com.example.emulator",
                )
            }

            // Intent type picker
            SettingsGroup("Launch Method")

            IntentType.entries.forEach { type ->
                SettingsRow(
                    label    = type.name,
                    sublabel = intentTypeDescription(type),
                    trailing = {
                        if (editorState.intentType == type) {
                            Text("✓", color = EditorAccent)
                        }
                    },
                    onClick = { onIntentTypeChange(type) },
                )
            }

            // ── Optional fields ───────────────────────────────────────────
            SettingsGroup("Optional")

            EditorTextField(
                label         = "Activity Class",
                value         = editorState.activityClass,
                onValueChange = onActivityClassChange,
                placeholder   = "e.g. com.example.emulator.MainActivity (leave blank for default)",
            )

            EditorTextField(
                label         = "Supported Platforms",
                value         = editorState.supportedPlatformIds,
                onValueChange = onPlatformIdsChange,
                placeholder   = "psx,psp,ps2  (comma-separated platform IDs)",
            )

            EditorTextField(
                label         = "MIME Type",
                value         = editorState.mimeType,
                onValueChange = onMimeTypeChange,
                placeholder   = "e.g. application/octet-stream",
            )

            if (editorState.intentType == IntentType.CUSTOM_COMMAND) {
                EditorTextField(
                    label         = "Custom Command",
                    value         = editorState.customCommand,
                    onValueChange = onCustomCommandChange,
                    placeholder   = "Shell command with {file} and {package} tokens",
                )
            }

            EditorTextField(
                label         = "Notes",
                value         = editorState.notes,
                onValueChange = onNotesChange,
                placeholder   = "Optional notes for this profile",
                singleLine    = false,
            )

            // ── URI flags ─────────────────────────────────────────────────
            SettingsGroup("URI Flags")

            SettingsToggleRow(
                label    = "Use File URI",
                sublabel = "Pass rom path as file:// URI (most emulators)",
                checked  = editorState.useFileUri,
                onToggle = onUseFileUriChange,
            )

            SettingsToggleRow(
                label    = "Use SAF URI",
                sublabel = "Pass SAF content:// URI instead of file path",
                checked  = editorState.useSafUri,
                onToggle = onUseSafUriChange,
            )

            // ── Advanced ──────────────────────────────────────────────────
            SettingsGroup("Advanced")

            EditorTextField(
                label         = "Intent Action",
                value         = editorState.intentActionText,
                onValueChange = onIntentActionChange,
                placeholder   = "e.g. me.magnum.melonds.LAUNCH_ROM (blank = default)",
            )
            EditorTextField(
                label         = "Intent Extras",
                value         = editorState.intentExtrasText,
                onValueChange = onIntentExtrasChange,
                placeholder   = "key=value per line. {rom_path}, {rom_uri}, {core_path} tokens. true/false = boolean.",
                singleLine    = false,
            )
            EditorTextField(
                label         = "Intent Flags",
                value         = editorState.intentFlagsText,
                onValueChange = onIntentFlagsChange,
                placeholder   = "CLEAR_TASK, CLEAR_TOP, NEW_TASK (comma-separated)",
            )
            EditorTextField(
                label         = "Intent Category",
                value         = editorState.intentCategoryText,
                onValueChange = onIntentCategoryChange,
                placeholder   = "e.g. android.intent.category.LEANBACK_LAUNCHER",
            )
            EditorTextField(
                label         = "RetroArch Core Path",
                value         = editorState.coreText,
                onValueChange = onCoreChange,
                placeholder   = "/data/data/com.retroarch/cores/xxx_libretro_android.so",
            )
            EditorTextField(
                label         = "Supported Extensions",
                value         = editorState.extensionsText,
                onValueChange = onExtensionsChange,
                placeholder   = "iso,cso,chd  (informational)",
            )

            // ── Error message ─────────────────────────────────────────────
            editorState.errorMessage?.let { msg ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text     = msg,
                    color    = EditorError,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
                )
            }

            // ── Test ──────────────────────────────────────────────────────
            SettingsGroup("Test")

            SettingsRow(
                label    = "Test Launch with a ROM",
                sublabel = "Pick a scanned ROM, preview the intent, and try launching",
                onClick  = onTestLaunch,
            )

            // ── Actions ───────────────────────────────────────────────────
            SettingsGroup("Actions")

            SettingsRow(
                label    = if (editorState.isSaving) "Saving…" else "Save Profile",
                sublabel = if (editorState.isNew) "Add to custom profiles" else "Update this profile",
                onClick  = if (editorState.isSaving) null else onSave,
            )

            onDelete?.let { doDelete ->
                SettingsRow(
                    label    = "Delete Profile",
                    sublabel = "Remove this custom profile permanently",
                    trailing = { Text("Delete", color = EditorError) },
                    onClick  = doDelete,
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// Confirm-to-edit: navigating onto the field no longer auto-opens the keyboard; the user
// presses SELECT (A) to start typing. Delegates to the shared settings field.
@Composable
private fun EditorTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    singleLine: Boolean = true,
) {
    SettingsTextFieldRow(
        label         = label,
        value         = value,
        onValueChange = onValueChange,
        placeholder   = placeholder,
        singleLine    = singleLine,
    )
}

private fun intentTypeDescription(type: IntentType) = when (type) {
    IntentType.ACTION_VIEW     -> "Intent.ACTION_VIEW — most emulators (PPSSPP, Dolphin, DuckStation…)"
    IntentType.COMPONENT       -> "Explicit component — RetroArch and apps needing direct class target"
    IntentType.SHORTCUT        -> "Home screen shortcut — Winlator, GameHub and custom launchers"
    IntentType.CUSTOM_COMMAND  -> "Shell command override — advanced; specify full command below"
}
