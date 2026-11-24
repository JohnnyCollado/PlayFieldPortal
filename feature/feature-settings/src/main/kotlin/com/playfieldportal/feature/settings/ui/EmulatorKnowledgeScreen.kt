package com.playfieldportal.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.playfieldportal.core.data.kb.PlatformGain
import com.playfieldportal.core.ui.components.PfpCheckMark
import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.feature.launcher.kb.KbUserFile
import com.playfieldportal.feature.settings.R
import com.playfieldportal.feature.settings.viewmodel.EmulatorKnowledgeUiState
import com.playfieldportal.feature.settings.viewmodel.EmulatorKnowledgeViewModel

private val VerifiedGreen = Color(0xFF4CAF50)
private val RemoveRed = Color(0xFFE55353)

/** Knowledge files are JSON, but file managers label them with any of these. */
private val KNOWLEDGE_FILE_MIME = arrayOf("application/json", "text/plain", "application/octet-stream")

/** The suggested name in the save dialog. */
private const val EXPORT_FILE_NAME = "pfp-emulators.json"

/** Settings > Emulators > Emulator knowledge (`settings_emulators_knowledge`). */
@Composable
fun EmulatorKnowledgeScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EmulatorKnowledgeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importFrom(uri)
    }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        viewModel.exportTo(uri)
    }
    EmulatorKnowledgeContent(
        state = state,
        onBack = onBack,
        onImport = { importPicker.launch(KNOWLEDGE_FILE_MIME) },
        onToggleReviewItem = viewModel::toggleReviewItem,
        onConfirmImport = viewModel::confirmImport,
        onCancelImport = viewModel::cancelImport,
        onDismissImportNotice = viewModel::dismissImportNotice,
        onExport = viewModel::openExport,
        onToggleExportItem = viewModel::toggleExportItem,
        onToggleExportAll = viewModel::toggleExportAll,
        onConfirmExport = { exportPicker.launch(EXPORT_FILE_NAME) },
        onCancelExport = viewModel::cancelExport,
        onDismissExportNotice = viewModel::dismissExportNotice,
        onCheckNow = viewModel::checkNow,
        onSetAutoUpdate = viewModel::setAutoUpdate,
        onRemoveFile = viewModel::removeFile,
        onRequestReset = viewModel::requestReset,
        onConfirmReset = viewModel::confirmReset,
        onDismissReset = viewModel::dismissReset,
        modifier = modifier,
    )
}

/**
 * The stateless screen body. An open import review or export picker replaces the whole
 * screen with its own sub-screen ([EmulatorKnowledgeReview], [EmulatorKnowledgeExport]).
 */
@Composable
internal fun EmulatorKnowledgeContent(
    state: EmulatorKnowledgeUiState,
    onBack: () -> Unit,
    onCheckNow: () -> Unit,
    onSetAutoUpdate: (Boolean) -> Unit,
    onRemoveFile: (String) -> Unit,
    onRequestReset: () -> Unit,
    onConfirmReset: () -> Unit,
    onDismissReset: () -> Unit,
    modifier: Modifier = Modifier,
    onImport: () -> Unit = {},
    onToggleReviewItem: (String) -> Unit = {},
    onConfirmImport: () -> Unit = {},
    onCancelImport: () -> Unit = {},
    onDismissImportNotice: () -> Unit = {},
    onExport: () -> Unit = {},
    onToggleExportItem: (String) -> Unit = {},
    onToggleExportAll: () -> Unit = {},
    onConfirmExport: () -> Unit = {},
    onCancelExport: () -> Unit = {},
    onDismissExportNotice: () -> Unit = {},
) {
    // Remove, from a file's row, asks here first; the view model's removeFile is one step.
    var pendingRemove by remember { mutableStateOf<KbUserFile?>(null) }
    val removeModal = rememberSettingsModal(
        pendingRemove?.let { file ->
            PfpModalSpec.Confirm(
                key = "kb_remove_${file.id}",
                title = stringResource(R.string.lib_kb_remove_title),
                message = stringResource(R.string.lib_kb_remove_message, file.displayName),
                confirmLabel = stringResource(R.string.lib_kb_remove),
                destructive = true,
                onConfirm = { pendingRemove = null; onRemoveFile(file.id) },
                onCancel = { pendingRemove = null },
            )
        },
    )
    val resetModal = rememberSettingsModal(
        if (state.confirmResetVisible) {
            PfpModalSpec.Confirm(
                key = "kb_reset",
                title = stringResource(R.string.lib_kb_reset_title),
                message = stringResource(R.string.lib_kb_reset_message),
                confirmLabel = stringResource(R.string.lib_kb_reset),
                destructive = true,
                onConfirm = onConfirmReset,
                onCancel = onDismissReset,
            )
        } else {
            null
        },
    )

    val importNoticeModal = rememberSettingsModal(
        state.importNotice?.let { notice ->
            val message = notice.asString()
            PfpModalSpec.Notice(
                key = "kb_import_notice:$message",
                title = stringResource(R.string.lib_kb_import_failed_title),
                message = message,
                onDismiss = onDismissImportNotice,
            )
        },
    )

    val exportNoticeModal = rememberSettingsModal(
        state.exportNotice?.let { notice ->
            val message = notice.message.asString()
            PfpModalSpec.Notice(
                key = "kb_export_notice:$message",
                title = notice.title.asString(),
                message = message,
                onDismiss = onDismissExportNotice,
            )
        },
    )

    val review = state.review
    val export = state.export
    if (export != null) {
        EmulatorKnowledgeExport(
            picker = export,
            onToggle = onToggleExportItem,
            onToggleAll = onToggleExportAll,
            onExport = onConfirmExport,
            onCancel = onCancelExport,
            modifier = modifier,
        )
    } else if (review != null) {
        EmulatorKnowledgeReview(
            review = review,
            onToggle = onToggleReviewItem,
            onConfirm = onConfirmImport,
            onCancel = onCancelImport,
            modifier = modifier,
        )
    } else {
        SettingsScaffold(
            title = stringResource(R.string.settings_title),
            subtitle = stringResource(R.string.lib_kb_title),
            onBack = onBack,
            modifier = modifier.fillMaxSize(),
            modalOpen = removeModal.open || resetModal.open || importNoticeModal.open || exportNoticeModal.open,
            // A modal is a hard input boundary: nothing behind it sees a press.
            onInterceptAction = { removeModal.intercept(it) || resetModal.intercept(it) || importNoticeModal.intercept(it) || exportNoticeModal.intercept(it) },
        ) {
            val scrollState = rememberScrollState()
            LocalSettingsScrollStateRegistrar.current(scrollState)
            Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
                SettingsGroup(stringResource(R.string.lib_kb_group_status))
                SettingsRow(
                    label = state.statusText.asString(),
                    sublabel = state.errorText?.asString(),
                    focusKey = "knowledge_status",
                    leading = if (state.officialApplied) {
                        { PfpCheckMark(VerifiedGreen) }
                    } else {
                        null
                    },
                )
                if (state.gainedFileTypes.isNotEmpty()) {
                    SettingsRow(
                        label = stringResource(R.string.lib_kb_new_file_types),
                        sublabel = stringResource(R.string.lib_kb_new_file_types_sub, gainedSummary(state.gainedFileTypes)),
                        focusKey = "knowledge_gained",
                    )
                }

                SettingsGroup(stringResource(R.string.lib_kb_group_updates))
                SettingsRow(
                    label = stringResource(R.string.lib_kb_check),
                    sublabel = if (state.updatesConfigured) {
                        state.checkResult?.asString() ?: stringResource(R.string.lib_kb_check_sub)
                    } else {
                        stringResource(R.string.lib_kb_not_available)
                    },
                    focusKey = "knowledge_check",
                    trailing = if (state.isChecking) {
                        { KnowledgeValueText(stringResource(R.string.lib_kb_checking)) }
                    } else {
                        null
                    },
                    enabled = state.updatesConfigured && !state.isChecking,
                    onClick = onCheckNow,
                )
                if (state.updatesConfigured) {
                    SettingsToggleRow(
                        label = stringResource(R.string.lib_kb_auto_update),
                        sublabel = stringResource(R.string.lib_kb_auto_update_sub),
                        focusKey = "knowledge_auto_update",
                        checked = state.autoUpdate,
                        onToggle = onSetAutoUpdate,
                    )
                } else {
                    SettingsRow(
                        label = stringResource(R.string.lib_kb_auto_update),
                        sublabel = stringResource(R.string.lib_kb_not_available),
                        focusKey = "knowledge_auto_update",
                        enabled = false,
                    )
                }

                SettingsGroup(stringResource(R.string.lib_kb_group_files))
                SettingsRow(
                    label = stringResource(R.string.lib_kb_import),
                    sublabel = stringResource(R.string.lib_kb_import_sub),
                    focusKey = "knowledge_import",
                    onClick = onImport,
                )
                if (state.userFiles.isNotEmpty()) {
                    SettingsGroup(stringResource(R.string.lib_kb_group_your_files))
                    state.userFiles.forEach { file ->
                        SettingsRow(
                            label = file.displayName,
                            sublabel = if (file.unreadable) {
                                stringResource(R.string.lib_kb_file_unreadable_sub)
                            } else {
                                pluralStringResource(R.plurals.lib_kb_file_sub, file.emulatorCount, file.emulatorCount)
                            },
                            focusKey = "knowledge_file_${file.id}",
                            labelTrailing = { FileBadges(file) },
                            actions = listOf(
                                SettingsRowAction(
                                    label = stringResource(R.string.lib_kb_remove_file, file.displayName),
                                    onClick = { pendingRemove = file },
                                    actionFocusBackgroundColor = lerp(RemoveRed, Color.Black, 0.50f),
                                    shortLabel = stringResource(R.string.lib_kb_remove),
                                ) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.lib_kb_remove_file, file.displayName),
                                        tint = RemoveRed,
                                        modifier = Modifier
                                            .background(Color.Black.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                                            .padding(4.dp),
                                    )
                                },
                            ),
                        )
                    }
                }
                SettingsRow(
                    label = stringResource(R.string.lib_kb_export),
                    sublabel = stringResource(R.string.lib_kb_export_sub),
                    focusKey = "knowledge_export",
                    onClick = onExport,
                )

                SettingsGroup(stringResource(R.string.lib_kb_group_reset))
                SettingsRow(
                    label = stringResource(R.string.lib_kb_reset_row),
                    sublabel = stringResource(R.string.lib_kb_reset_row_sub),
                    focusKey = "knowledge_reset",
                    onClick = onRequestReset,
                )
            }
        }
    }
    removeModal.Content()
    resetModal.Content()
    importNoticeModal.Content()
    exportNoticeModal.Content()
}

/** "PlayStation 2: chd, cso; Nintendo Switch: nsz." */
internal fun gainedSummary(gains: List<PlatformGain>): String =
    gains.joinToString("; ") { gain ->
        "${platformNames[gain.platformId] ?: gain.platformId}: ${gain.added.joinToString(", ")}"
    } + "."

@Composable
private fun FileBadges(file: KbUserFile) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        KnowledgeBadge(stringResource(R.string.lib_kb_badge_custom), SettingsAccent.copy(alpha = 0.55f))
        if (file.unreadable) KnowledgeBadge(stringResource(R.string.lib_kb_badge_unreadable), RemoveRed.copy(alpha = 0.7f))
    }
}

@Composable
internal fun KnowledgeBadge(text: String, background: Color) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier
            .background(background, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// Same styling as SettingsValueRow's value.
@Composable
private fun KnowledgeValueText(text: String) {
    Text(text = text, color = SettingsText, fontSize = 13.sp, style = TextStyle(shadow = SettingsTextShadow))
}
