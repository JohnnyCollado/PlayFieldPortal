package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.feature.settings.viewmodel.KbExportPicker

private val ExportHelperItems = listOf(
    ControllerPromptItem(GamepadAction.SELECT, "Toggle"),
    ControllerPromptItem(GamepadAction.BACK, "Cancel"),
)

/**
 * The export picker (AD-12), a sub-screen of the knowledge screen: a checkbox per shareable emulator
 * (all ticked), the ones that cannot be shared and why, and Cancel / the export button. Back is a cancel.
 * Nothing is written until [onExport] and the user has named the file.
 */
@Composable
internal fun EmulatorKnowledgeExport(
    picker: KbExportPicker,
    onToggle: (String) -> Unit,
    onToggleAll: () -> Unit,
    onExport: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val custom = picker.entries.filter { it.isCustom }
    val edited = picker.entries.filterNot { it.isCustom }

    SettingsScaffold(
        title = "Export your emulators",
        subtitle = "Saved as one .json you can share",
        onBack = onCancel,
        modifier = modifier.fillMaxSize(),
        helperFooterItems = ExportHelperItems,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
            ReviewCheckRow(
                label = "Select all",
                sublabel = "${picker.selectedCount} of ${picker.entries.size} selected",
                focusKey = "knowledge_export_all",
                checked = picker.allSelected,
                onToggle = onToggleAll,
            )
            if (custom.isNotEmpty()) {
                SettingsGroup("Custom emulators")
                custom.forEach { ExportRow(it, "${it.packageName} · ${consoleNames(it)}", picker, onToggle) }
            }
            if (edited.isNotEmpty()) {
                SettingsGroup("Edited official emulators")
                edited.forEach { ExportRow(it, "Your changes only · ${consoleNames(it)}", picker, onToggle) }
            }
            SettingsRow(
                label = "Only launch settings are saved. No game list, ROM paths or account details.",
                focusKey = "knowledge_export_privacy",
            )
            if (picker.cantShare.isNotEmpty()) {
                SettingsGroup("Can't be shared")
                picker.cantShare.forEach { item ->
                    SettingsRow(
                        label = item.name,
                        sublabel = item.reason,
                        focusKey = "knowledge_export_cant_${item.profileId}",
                    )
                }
            }
            SettingsGroup("Finish")
            SettingsRow(label = "Cancel", focusKey = "knowledge_export_cancel", onClick = onCancel)
            SettingsRow(
                label = picker.confirmLabel,
                focusKey = "knowledge_export_confirm",
                enabled = picker.exportEnabled,
                onClick = onExport,
            )
        }
    }
}

@Composable
private fun ExportRow(profile: EmulatorProfile, sublabel: String, picker: KbExportPicker, onToggle: (String) -> Unit) {
    ReviewCheckRow(
        label = profile.name,
        sublabel = sublabel,
        focusKey = "knowledge_export_${profile.id}",
        checked = profile.id in picker.selectedIds,
        onToggle = { onToggle(profile.id) },
    )
}

private fun consoleNames(profile: EmulatorProfile): String =
    profile.supportedPlatformIds.joinToString(", ") { platformNames[it] ?: it }
