package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.feature.settings.R
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.playfieldportal.core.ui.components.PfpModalSpec
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.sound.LocalMenuSounds
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.feature.settings.viewmodel.LogFileItem
import com.playfieldportal.feature.settings.viewmodel.LogsSettingsViewModel
import timber.log.Timber
import java.io.File

/**
 * System ▸ Logs — the session log files, each opened in an external viewer (Confirm / tap) or
 * shared through the system sheet (the row's inline Share action, or the north face button), plus
 * a confirmed Clear All Logs.
 *
 * Share is bound to the north-facing button through [MediaRowShortcuts] so it follows the user's
 * X/Y layout the same way the Sound screen's shortcuts do, and the helper footer names whatever the
 * cursor is on instead of a fixed legend in the section header.
 */
@Composable
fun LogsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LogsSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Returning from the viewer or the share sheet: sizes grow while the session keeps logging.
    LifecycleResumeEffect(Unit) {
        viewModel.refresh()
        onPauseOrDispose { }
    }

    // What the cursor is on — drives the footer prompts and the Share shortcut. Each is cleared
    // only by its OWN row losing focus, so the order the row/action focus events land in can't
    // wipe the value the other one just set.
    var focusedLog by remember { mutableStateOf<String?>(null) }
    var shareFocusedLog by remember { mutableStateOf<String?>(null) }
    var clearFocused by remember { mutableStateOf(false) }

    val shareTarget = shareFocusedLog ?: focusedLog
    val footerItems = when {
        shareFocusedLog != null -> listOf(
            ControllerPromptItem(GamepadAction.SELECT, stringResource(R.string.sets_logs_prompt_share)),
            ControllerPromptItem(GamepadAction.BACK, stringResource(R.string.sets_prompt_back)),
        )
        focusedLog != null -> listOf(
            ControllerPromptItem(GamepadAction.SELECT, stringResource(R.string.sets_logs_prompt_open)),
            ControllerPromptItem(MediaRowShortcuts.northFace(state.xyLayout), stringResource(R.string.sets_logs_prompt_share)),
            ControllerPromptItem(GamepadAction.BACK, stringResource(R.string.sets_prompt_back)),
        )
        clearFocused && state.logFiles.isNotEmpty() -> listOf(
            ControllerPromptItem(GamepadAction.SELECT, stringResource(R.string.sets_logs_prompt_clear)),
            ControllerPromptItem(GamepadAction.BACK, stringResource(R.string.sets_prompt_back)),
        )
        else -> SettingsDefaultHelperItems
    }

    // Consumed by the interceptor below, so the scaffold never sees it to voice it.
    val menuSounds = LocalMenuSounds.current

    val logCount = state.logFiles.size
    val modal = rememberSettingsModal(
        if (state.confirmClearVisible) {
            PfpModalSpec.Confirm(
                key = "clear_logs",
                title = stringResource(R.string.sets_logs_clear_title),
                message = pluralStringResource(R.plurals.sets_logs_clear_message, logCount, logCount, state.totalSize),
                confirmLabel = stringResource(R.string.sets_logs_clear_confirm),
                destructive = true,
                onConfirm = viewModel::confirmClear,
                onCancel = viewModel::dismissClear,
            )
        } else {
            null
        },
    )

    SettingsScaffold(
        title = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.sets_logs_title),
        onBack = onBack,
        modifier = modifier,
        helperFooterItems = footerItems,
        modalOpen = modal.open,
        onInterceptAction = { action ->
            // A modal is a hard input boundary: nothing behind it sees a press.
            if (modal.intercept(action)) return@SettingsScaffold true
            val target = shareTarget
            if (target != null && !state.confirmClearVisible &&
                MediaRowShortcuts.isNorthFace(action, state.xyLayout)
            ) {
                // Consumed, so the scaffold stays silent — the shortcut voices itself.
                menuSounds.play(MenuSound.SELECT)
                shareLogFile(context, target)
                true
            } else {
                false
            }
        },
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            // Nothing composes until the directory has been read: rows that arrive a moment later
            // would lose the screen's first focus to Clear All Logs, the only action present.
            if (!state.loaded) return@Column

            SettingsGroup(stringResource(R.string.sets_logs_group_files))

            if (state.logFiles.isEmpty()) {
                SettingsRow(
                    label = stringResource(R.string.sets_logs_empty),
                    sublabel = stringResource(R.string.sets_logs_empty_help),
                    focusKey = "logs_empty",
                    onClick = { viewModel.refresh() },
                )
            }
            state.logFiles.forEach { file ->
                LogFileRow(
                    file = file,
                    onOpen = { openLogExternally(context, file.name) },
                    onShare = { shareLogFile(context, file.name) },
                    onRowFocusChanged = { focused ->
                        if (focused) focusedLog = file.name
                        else if (focusedLog == file.name) focusedLog = null
                    },
                    onShareFocusChanged = { focused ->
                        if (focused) shareFocusedLog = file.name
                        else if (shareFocusedLog == file.name) shareFocusedLog = null
                    },
                )
            }

            SettingsGroup(stringResource(R.string.sets_logs_group_manage))

            val hasLogs = state.logFiles.isNotEmpty()
            SettingsRow(
                label = stringResource(R.string.sets_logs_clear_row),
                sublabel = if (hasLogs) {
                    stringResource(R.string.sets_logs_clear_row_help)
                } else {
                    stringResource(R.string.sets_logs_clear_row_none)
                },
                focusKey = "logs_clear",
                trailing = if (hasLogs) {
                    { LogsValueText(state.totalSummary.asString()) }
                } else {
                    null
                },
                enabled = hasLogs,
                destructive = true,
                onFocusChangedExternal = { clearFocused = it },
                onClick = { viewModel.requestClear() },
            )

            SettingsGroup(stringResource(R.string.sets_logs_group_about))

            SettingsRow(
                label = stringResource(R.string.sets_logs_saved),
                sublabel = stringResource(R.string.sets_logs_saved_help),
                focusKey = "logs_about",
            )
        }
    }

    modal.Content()
}

/**
 * One log file: name (+ CURRENT for the file this session is writing), when it started, its size,
 * and the inline Share action — reached with RIGHT on a controller, tapped directly in touch mode.
 */
@Composable
private fun LogFileRow(
    file: LogFileItem,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onRowFocusChanged: (Boolean) -> Unit,
    onShareFocusChanged: (Boolean) -> Unit,
) {
    val shareLabel = stringResource(R.string.sets_logs_share_file, file.name)
    SettingsRow(
        label = file.name,
        sublabel = file.startedLabel?.asString(),
        focusKey = "logs_file_${file.name}",
        labelTrailing = if (file.isCurrent) {
            { CurrentSessionTag() }
        } else {
            null
        },
        trailing = { LogsValueText(file.size) },
        actions = listOf(
            SettingsRowAction(
                label = shareLabel,
                onClick = onShare,
                // The same treatment as the Sound screen's Preview action.
                actionFocusBackgroundColor = lerp(SettingsAccent, Color.Black, 0.50f),
                onFocusChanged = onShareFocusChanged,
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = shareLabel,
                    tint = SettingsAccent,
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                        .padding(4.dp),
                )
            },
        ),
        onFocusChangedExternal = onRowFocusChanged,
        onClick = onOpen,
    )
}

@Composable
private fun CurrentSessionTag() {
    Text(
        text = stringResource(R.string.sets_logs_current),
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier
            .background(SettingsAccent.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// Same styling as SettingsValueRow's value: body-text colour, never the accent (see its note).
@Composable
private fun LogsValueText(text: String) {
    Text(
        text = text,
        color = SettingsText,
        fontSize = 13.sp,
        style = TextStyle(shadow = SettingsTextShadow),
    )
}

// Content URI for one log through the app's FileProvider — the receiving app gets read access
// to that single file only. Null when the file no longer exists.
private fun logFileUri(context: Context, fileName: String): Uri? {
    val file = File(context.filesDir, "logs/$fileName")
    if (!file.exists()) return null
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/** Opens one log in an external text viewer via the system "Open with" chooser. */
private fun openLogExternally(context: Context, fileName: String) {
    runCatching {
        val uri = logFileUri(context, fileName) ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "text/plain")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.sets_logs_open_with))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { Timber.w(it, "Could not open log file externally") }
}

// Shares one log via the system share sheet. Files are already redacted at write time, so
// nothing sensitive can leave even here.
private fun shareLogFile(context: Context, fileName: String) {
    runCatching {
        val uri = logFileUri(context, fileName) ?: return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.sets_logs_share_subject, fileName))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, context.getString(R.string.sets_logs_share_chooser))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { Timber.w(it, "Could not share log file") }
}
