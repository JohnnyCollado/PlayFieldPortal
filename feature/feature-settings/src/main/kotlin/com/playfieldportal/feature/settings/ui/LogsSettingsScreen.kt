package com.playfieldportal.feature.settings.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
            ControllerPromptItem(GamepadAction.SELECT, "Share"),
            ControllerPromptItem(GamepadAction.BACK, "Back"),
        )
        focusedLog != null -> listOf(
            ControllerPromptItem(GamepadAction.SELECT, "Open"),
            ControllerPromptItem(MediaRowShortcuts.northFace(state.xyLayout), "Share"),
            ControllerPromptItem(GamepadAction.BACK, "Back"),
        )
        clearFocused && state.logFiles.isNotEmpty() -> listOf(
            ControllerPromptItem(GamepadAction.SELECT, "Clear"),
            ControllerPromptItem(GamepadAction.BACK, "Back"),
        )
        else -> SettingsDefaultHelperItems
    }

    SettingsScaffold(
        title = "Settings",
        subtitle = "Logs",
        onBack = onBack,
        modifier = modifier,
        helperFooterItems = footerItems,
        onInterceptAction = { action ->
            val target = shareTarget
            if (target != null && !state.confirmClearVisible &&
                MediaRowShortcuts.isNorthFace(action, state.xyLayout)
            ) {
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

            SettingsGroup("Log Files")

            if (state.logFiles.isEmpty()) {
                SettingsRow(
                    label = "No log files yet",
                    sublabel = "A new log starts with every app launch. Select to check again.",
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

            SettingsGroup("Manage")

            val hasLogs = state.logFiles.isNotEmpty()
            SettingsRow(
                label = "Clear All Logs",
                sublabel = if (hasLogs) {
                    "Deletes every saved log. This session keeps logging into a fresh file."
                } else {
                    "Nothing to clear"
                },
                focusKey = "logs_clear",
                trailing = if (hasLogs) {
                    { LogsValueText(state.totalSummary) }
                } else {
                    null
                },
                enabled = hasLogs,
                onFocusChangedExternal = { clearFocused = it },
                onClick = { viewModel.requestClear() },
            )

            SettingsGroup("About Logs")

            SettingsRow(
                label = "What gets saved",
                sublabel = "Only the most recent sessions are kept (about 2 MB at most). Passwords, " +
                    "tokens, account names and emails are removed before anything is written.",
                focusKey = "logs_about",
            )
        }
    }

    if (state.confirmClearVisible) {
        val count = state.logFiles.size
        AlertDialog(
            onDismissRequest = viewModel::dismissClear,
            title = { Text("Clear All Logs?") },
            text = {
                Text(
                    "${if (count == 1) "The log file" else "All $count log files"} " +
                        "(${state.totalSize}) will be deleted. If you're " +
                        "about to report a problem, share the log first. A cleared log can't be recovered."
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmClear) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissClear) { Text("Cancel") }
            },
        )
    }
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
    SettingsRow(
        label = file.name,
        sublabel = file.startedLabel,
        focusKey = "logs_file_${file.name}",
        labelTrailing = if (file.isCurrent) {
            { CurrentSessionTag() }
        } else {
            null
        },
        trailing = { LogsValueText(file.size) },
        actions = listOf(
            SettingsRowAction(
                label = "Share ${file.name}",
                onClick = onShare,
                // The same treatment as the Sound screen's Preview action.
                actionFocusBackgroundColor = lerp(SettingsAccent, Color.Black, 0.50f),
                onFocusChanged = onShareFocusChanged,
            ) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Share ${file.name}",
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
        text = "CURRENT",
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
            Intent.createChooser(intent, "Open log with")
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
            putExtra(Intent.EXTRA_SUBJECT, "Play Field Portal log — $fileName")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Share log file")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { Timber.w(it, "Could not share log file") }
}
