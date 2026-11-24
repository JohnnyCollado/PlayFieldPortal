package com.playfieldportal.feature.settings.ui

import androidx.annotation.StringRes
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.IntentType
import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.feature.settings.R
import com.playfieldportal.feature.settings.viewmodel.EmulatorTemplate
import com.playfieldportal.feature.settings.viewmodel.ProfileEditorState

private val EditorSubtext  = Color(0xFF888888)
private val EditorAccent   = Color(0xFF4A90D9)
private val EditorError    = Color(0xFFE57373)

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
    val subtitle = stringResource(if (editorState.isNew) R.string.lib_prof_new else R.string.lib_prof_edit)

    // Custom-intent field guide (Advanced section). Shown while the ⓘ row is focused
    // ("hovered" on a controller); pressing SELECT pins it open so it stays visible while
    // the user edits the fields below.
    var intentHelpPinned  by remember { mutableStateOf(false) }
    var intentHelpFocused by remember { mutableStateOf(false) }

    SettingsScaffold(
        title    = stringResource(R.string.lib_emu_title),
        subtitle = subtitle,
        onBack   = onCancel,
        modifier = modifier,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
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
            SettingsGroup(stringResource(R.string.lib_prof_group_templates))
            EmulatorTemplate.entries.forEach { template ->
                SettingsRow(
                    label    = stringResource(template.labelRes),
                    sublabel = stringResource(template.descriptionRes),
                    onClick  = { onApplyTemplate(template) },
                )
            }

            // ── Required fields ───────────────────────────────────────────
            SettingsGroup(stringResource(R.string.lib_prof_group_required))

            EditorTextField(
                label       = stringResource(R.string.lib_prof_display_name),
                value       = editorState.name,
                onValueChange = onNameChange,
                placeholder = stringResource(R.string.lib_prof_display_name_hint),
            )

            EditorTextField(
                label         = stringResource(R.string.lib_prof_package),
                value         = editorState.packageName,
                onValueChange = onPackageNameChange,
                placeholder   = stringResource(R.string.lib_prof_package_hint),
            )

            // Intent type picker
            SettingsGroup(stringResource(R.string.lib_prof_group_launch))

            // CUSTOM_COMMAND is refused at launch (AD-1), so it is no longer offered.
            IntentType.entries.filter { it != IntentType.CUSTOM_COMMAND }.forEach { type ->
                SettingsRow(
                    label    = type.name,
                    sublabel = stringResource(intentTypeDescription(type)),
                    trailing = {
                        if (editorState.intentType == type) {
                            com.playfieldportal.core.ui.components.PfpCheckMark(EditorAccent)
                        }
                    },
                    onClick = { onIntentTypeChange(type) },
                )
            }

            // ── Optional fields ───────────────────────────────────────────
            SettingsGroup(stringResource(R.string.lib_prof_group_optional))

            EditorTextField(
                label         = stringResource(R.string.lib_prof_activity),
                value         = editorState.activityClass,
                onValueChange = onActivityClassChange,
                placeholder   = stringResource(R.string.lib_prof_activity_hint),
            )

            EditorTextField(
                label         = stringResource(R.string.lib_prof_platforms),
                value         = editorState.supportedPlatformIds,
                onValueChange = onPlatformIdsChange,
                placeholder   = stringResource(R.string.lib_prof_platforms_hint),
            )

            EditorTextField(
                label         = stringResource(R.string.lib_prof_mime),
                value         = editorState.mimeType,
                onValueChange = onMimeTypeChange,
                placeholder   = stringResource(R.string.lib_prof_mime_hint),
            )

            EditorTextField(
                label         = stringResource(R.string.lib_prof_notes),
                value         = editorState.notes,
                onValueChange = onNotesChange,
                placeholder   = stringResource(R.string.lib_prof_notes_hint),
                singleLine    = false,
            )

            // ── URI flags ─────────────────────────────────────────────────
            SettingsGroup(stringResource(R.string.lib_prof_group_uri))

            SettingsToggleRow(
                label    = stringResource(R.string.lib_prof_use_file_uri),
                sublabel = stringResource(R.string.lib_prof_use_file_uri_sub),
                checked  = editorState.useFileUri,
                onToggle = onUseFileUriChange,
            )

            SettingsToggleRow(
                label    = stringResource(R.string.lib_prof_use_saf_uri),
                sublabel = stringResource(R.string.lib_prof_use_saf_uri_sub),
                checked  = editorState.useSafUri,
                onToggle = onUseSafUriChange,
            )

            // ── Advanced ──────────────────────────────────────────────────
            SettingsGroup(stringResource(R.string.lib_prof_group_advanced))

            // Info affordance for the custom-intent fields: focusing it (controller "hover")
            // reveals the guide; pressing SELECT pins it open so it stays while editing below.
            SettingsRow(
                label    = stringResource(R.string.lib_prof_intent_help),
                sublabel = stringResource(if (intentHelpPinned) R.string.lib_prof_intent_help_hide else R.string.lib_prof_intent_help_show),
                leading  = { Text("ⓘ", color = EditorAccent, fontSize = 18.sp) },
                onFocusChangedExternal = { intentHelpFocused = it },
                onClick  = { intentHelpPinned = !intentHelpPinned },
            )
            if (intentHelpPinned || intentHelpFocused) {
                CustomIntentHelp()
            }

            EditorTextField(
                label         = stringResource(R.string.lib_prof_intent_action),
                value         = editorState.intentActionText,
                onValueChange = onIntentActionChange,
                placeholder   = stringResource(R.string.lib_prof_intent_action_hint),
            )
            EditorTextField(
                label         = stringResource(R.string.lib_prof_intent_extras),
                value         = editorState.intentExtrasText,
                onValueChange = onIntentExtrasChange,
                placeholder   = stringResource(R.string.lib_prof_intent_extras_hint),
                singleLine    = false,
            )
            EditorTextField(
                label         = stringResource(R.string.lib_prof_intent_flags),
                value         = editorState.intentFlagsText,
                onValueChange = onIntentFlagsChange,
                placeholder   = stringResource(R.string.lib_prof_intent_flags_hint),
            )
            EditorTextField(
                label         = stringResource(R.string.lib_prof_intent_category),
                value         = editorState.intentCategoryText,
                onValueChange = onIntentCategoryChange,
                placeholder   = stringResource(R.string.lib_prof_intent_category_hint),
            )
            EditorTextField(
                label         = stringResource(R.string.lib_prof_core_path),
                value         = editorState.coreText,
                onValueChange = onCoreChange,
                placeholder   = "/data/data/com.retroarch/cores/xxx_libretro_android.so",
            )
            EditorTextField(
                label         = stringResource(R.string.lib_prof_extensions),
                value         = editorState.extensionsText,
                onValueChange = onExtensionsChange,
                placeholder   = stringResource(R.string.lib_prof_extensions_hint),
            )

            // ── Error message ─────────────────────────────────────────────
            editorState.errorMessage?.let { msg ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text     = msg.asString(),
                    color    = EditorError,
                    modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
                )
            }

            // ── Test ──────────────────────────────────────────────────────
            SettingsGroup(stringResource(R.string.lib_prof_group_test))

            SettingsRow(
                label    = stringResource(R.string.lib_prof_test),
                sublabel = stringResource(R.string.lib_prof_test_sub),
                onClick  = onTestLaunch,
            )

            // ── Actions ───────────────────────────────────────────────────
            SettingsGroup(stringResource(R.string.lib_prof_group_actions))

            SettingsRow(
                label    = stringResource(if (editorState.isSaving) R.string.lib_prof_saving else R.string.lib_prof_save),
                sublabel = stringResource(if (editorState.isNew) R.string.lib_prof_save_new_sub else R.string.lib_prof_save_edit_sub),
                onClick  = if (editorState.isSaving) null else onSave,
            )

            onDelete?.let { doDelete ->
                SettingsRow(
                    label    = stringResource(R.string.lib_prof_delete),
                    sublabel = stringResource(R.string.lib_prof_delete_sub),
                    trailing = { Text(stringResource(R.string.lib_prof_delete_button), color = EditorError) },
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

// Inline field guide for the Advanced custom-intent inputs. Non-focusable text — the D-pad
// cursor skips straight past it from the ⓘ row to the Intent Action field.
@Composable
private fun CustomIntentHelp() {
    Column(modifier = Modifier.padding(start = 64.dp, end = 48.dp, top = 2.dp, bottom = 12.dp)) {
        HelpSection(stringResource(R.string.lib_help_launch_method))
        HelpLine(stringResource(R.string.lib_help_launch_view))
        HelpLine(stringResource(R.string.lib_help_launch_component))

        HelpSection(stringResource(R.string.lib_help_action))
        HelpLine(stringResource(R.string.lib_help_action_line))
        HelpLine(stringResource(R.string.lib_help_action_examples))

        HelpSection(stringResource(R.string.lib_help_extras))
        HelpLine(stringResource(R.string.lib_help_extras_tokens))
        HelpLine(stringResource(R.string.lib_help_extras_rom_uri))
        HelpLine(stringResource(R.string.lib_help_extras_rom_path))
        HelpLine(stringResource(R.string.lib_help_extras_boolean))
        HelpLine(stringResource(R.string.lib_help_extras_examples))

        HelpSection(stringResource(R.string.lib_help_flags))
        HelpLine(stringResource(R.string.lib_help_flags_line))

        HelpSection(stringResource(R.string.lib_help_category))
        HelpLine(stringResource(R.string.lib_help_category_line))

        HelpSection(stringResource(R.string.lib_help_tip))
        HelpLine(stringResource(R.string.lib_help_tip_line1))
        HelpLine(stringResource(R.string.lib_help_tip_line2))
    }
}

@Composable
private fun HelpSection(text: String) {
    Text(
        text       = text,
        color      = EditorAccent,
        fontSize   = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier   = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun HelpLine(text: String) {
    Text(
        text     = text,
        color    = EditorSubtext,
        fontSize = 12.sp,
        modifier = Modifier.padding(bottom = 1.dp),
    )
}

@StringRes
private fun intentTypeDescription(type: IntentType): Int = when (type) {
    IntentType.ACTION_VIEW     -> R.string.lib_prof_type_view
    IntentType.COMPONENT       -> R.string.lib_prof_type_component
    IntentType.SHORTCUT        -> R.string.lib_prof_type_shortcut
    IntentType.CUSTOM_COMMAND  -> R.string.lib_prof_type_custom
}
