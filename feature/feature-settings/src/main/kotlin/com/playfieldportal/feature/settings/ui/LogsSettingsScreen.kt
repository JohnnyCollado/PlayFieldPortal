package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.feature.settings.viewmodel.LogsSettingsViewModel
import kotlinx.coroutines.launch

@Composable
fun LogsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LogsSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val stepPx = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.toPx() }

    // Viewer pane scroll state, hoisted so the controller can drive it.
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Logs",
        onBack   = onBack,
        modifier = modifier,
        // Viewer mode: while a log is open, the controller drives the CONTENT — ▲▼ scroll,
        // ◄ ► pan long lines, Confirm shares this file, Back closes the viewer (and only
        // then does Back leave the screen). Row navigation resumes once the viewer closes.
        onInterceptAction = { action ->
            if (state.selectedFile == null) return@SettingsScaffold false
            when (action) {
                com.playfieldportal.core.domain.model.GamepadAction.NAVIGATE_UP ->
                    { scope.launch { vScroll.animateScrollBy(-stepPx) }; true }
                com.playfieldportal.core.domain.model.GamepadAction.NAVIGATE_DOWN ->
                    { scope.launch { vScroll.animateScrollBy(stepPx) }; true }
                com.playfieldportal.core.domain.model.GamepadAction.NAVIGATE_LEFT ->
                    { scope.launch { hScroll.animateScrollBy(-stepPx) }; true }
                com.playfieldportal.core.domain.model.GamepadAction.NAVIGATE_RIGHT ->
                    { scope.launch { hScroll.animateScrollBy(stepPx) }; true }
                com.playfieldportal.core.domain.model.GamepadAction.SELECT ->
                    { state.selectedFile?.let { shareLogFile(context, it) }; true }
                com.playfieldportal.core.domain.model.GamepadAction.BACK ->
                    { viewModel.closeViewer(); true }
                else -> false
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Controls
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SettingsGroup("Log Files")

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
                        trailing = if (file.name == state.selectedFile) ({
                            Text("Viewing", color = SettingsAccent, fontSize = 12.sp)
                        }) else null,
                        onClick  = { viewModel.selectFile(file.name) },
                    )
                }

                if (state.selectedFile != null) {
                    SettingsRow(
                        label    = "Share This Log",
                        sublabel = "Send \"${state.selectedFile}\" — credentials and personal data are already redacted",
                        onClick  = { shareLogFile(context, state.selectedFile!!) },
                    )
                }

                SettingsRow(
                    label   = "Clear All Logs",
                    onClick = { viewModel.clearLogs() },
                )

                SettingsGroup(
                    if (state.selectedFile != null) "Log Viewer   —   ▲▼ scroll · ◄ ► pan · Ⓐ share · Ⓑ close"
                    else "Log Viewer"
                )
            }

            // Log content — monospace, controller-scrollable while a file is open
            Text(
                text       = state.logContent.ifBlank { "Select a log file above." },
                color      = SettingsSubtext,
                fontSize   = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp,
                modifier   = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .horizontalScroll(hScroll)
                    .verticalScroll(vScroll)
                    .padding(horizontal = 48.dp, vertical = 12.dp),
            )
        }
    }
}

// Shares one log file via the system share sheet (content:// through the app's FileProvider —
// the receiving app gets read access to that single file only). Files are already redacted
// at write time, so nothing sensitive can leave even here.
private fun shareLogFile(context: android.content.Context, fileName: String) {
    runCatching {
        val file = java.io.File(context.filesDir, "logs/$fileName")
        if (!file.exists()) return
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file,
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_SUBJECT, "Play Field Portal log — $fileName")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            android.content.Intent.createChooser(intent, "Share log file")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }.onFailure { timber.log.Timber.w(it, "Could not share log file") }
}
