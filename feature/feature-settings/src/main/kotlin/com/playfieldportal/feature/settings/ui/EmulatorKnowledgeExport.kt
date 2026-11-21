package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import com.playfieldportal.core.domain.model.EmulatorProfile
import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.feature.settings.R
import com.playfieldportal.feature.settings.viewmodel.KbExportPicker

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
        title = stringResource(R.string.lib_kb_export),
        subtitle = stringResource(R.string.lib_kb_export_title_sub),
        onBack = onCancel,
        modifier = modifier.fillMaxSize(),
        helperFooterItems = knowledgeHelperItems(),
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
            ReviewCheckRow(
                label = stringResource(R.string.lib_kb_export_select_all),
                sublabel = stringResource(R.string.lib_kb_export_selected, picker.selectedCount, picker.entries.size),
                focusKey = "knowledge_export_all",
                checked = picker.allSelected,
                onToggle = onToggleAll,
            )
            if (custom.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.lib_kb_group_custom))
                custom.forEach { ExportRow(it, "${it.packageName} · ${consoleNames(it)}", picker, onToggle) }
            }
            if (edited.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.lib_kb_group_edited))
                edited.forEach { ExportRow(it, stringResource(R.string.lib_kb_export_changes_only, consoleNames(it)), picker, onToggle) }
            }
            SettingsRow(
                label = stringResource(R.string.lib_kb_export_privacy),
                focusKey = "knowledge_export_privacy",
            )
            if (picker.cantShare.isNotEmpty()) {
                SettingsGroup(stringResource(R.string.lib_kb_group_cant_share))
                picker.cantShare.forEach { item ->
                    SettingsRow(
                        label = item.name,
                        sublabel = item.reason,
                        focusKey = "knowledge_export_cant_${item.profileId}",
                    )
                }
            }
            SettingsGroup(stringResource(R.string.lib_kb_group_finish))
            SettingsRow(label = stringResource(R.string.lib_kb_cancel), focusKey = "knowledge_export_cancel", onClick = onCancel)
            SettingsRow(
                label = picker.confirmLabel.asString(),
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
