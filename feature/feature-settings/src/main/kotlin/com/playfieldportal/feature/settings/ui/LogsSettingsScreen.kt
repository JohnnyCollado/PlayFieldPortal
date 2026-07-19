package com.playfieldportal.feature.settings.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.PspContextMenuOverlay
import com.playfieldportal.core.ui.components.PspMenuRow
import com.playfieldportal.feature.settings.viewmodel.LogsSettingsViewModel
import timber.log.Timber
import java.io.File

@Composable
fun LogsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LogsSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // The log row the controller cursor is hovering — the options-menu target. Cleared when a
    // non-log row takes focus (or logs are cleared), so the menu can never act on a stale name.
    var focusedLog by remember { mutableStateOf<String?>(null) }

    // Per-log context menu (options button), PSP-style like the Themes screen's card menu.
    var menuFor by remember { mutableStateOf<String?>(null) }
    var menuIndex by remember { mutableStateOf(0) }
    val menuRows = listOf(PspMenuRow("Share"))

    Box(modifier = modifier) {

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Logs",
        onBack   = onBack,
        modifier = Modifier.fillMaxSize(),
        onInterceptAction = { action ->
            val m = menuFor
            when {
                // Open menu captures ALL input while visible.
                m != null -> {
                    when (action) {
                        GamepadAction.NAVIGATE_UP   -> menuIndex = (menuIndex - 1).coerceAtLeast(0)
                        GamepadAction.NAVIGATE_DOWN -> menuIndex = (menuIndex + 1).coerceAtMost(menuRows.size - 1)
                        GamepadAction.SELECT        -> {
                            if (menuIndex == 0) shareLogFile(context, m)
                            menuFor = null
                        }
                        GamepadAction.BACK,
                        GamepadAction.LONG_PRESS,
                        GamepadAction.BUTTON_Y      -> menuFor = null
                        else -> Unit
                    }
                    true
                }
                // Options button (LONG_PRESS or BUTTON_Y, mapping-dependent) on a hovered log.
                action == GamepadAction.LONG_PRESS || action == GamepadAction.BUTTON_Y -> {
                    focusedLog?.let { menuFor = it; menuIndex = 0 }
                    true
                }
                else -> false
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsGroup("Log Files   —   Ⓐ open externally · Ⓨ options")

            if (state.logFiles.isEmpty()) {
                SettingsRow(
                    label    = "No log files yet",
                    sublabel = "A new log starts with every app launch — come back after something happens",
                )
            }
            state.logFiles.forEach { file ->
                SettingsRow(
                    label    = file.name,
                    sublabel = file.sizeKb,
                    onFocusChangedExternal = { focused -> if (focused) focusedLog = file.name },
                    onClick  = { openLogExternally(context, file.name) },
                )
            }

            SettingsRow(
                label   = "Clear All Logs",
                onFocusChangedExternal = { focused -> if (focused) focusedLog = null },
                onClick = {
                    focusedLog = null
                    viewModel.clearLogs()
                },
            )
        }
    }

    // Context menu over the whole screen (controller: hover + options button; touch can tap rows).
    menuFor?.let { name ->
        PspContextMenuOverlay(
            title          = name,
            rows           = menuRows,
            selectedIndex  = menuIndex,
            onRowActivated = { index ->
                if (index == 0) shareLogFile(context, name)
                menuFor = null
            },
            onDismiss      = { menuFor = null },
        )
    }

    }
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
