package com.playfieldportal.feature.settings.ui

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

@Composable
fun LogsSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LogsSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Logs",
        onBack   = onBack,
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // Controls
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SettingsGroup("Log Files  (7-day rolling)")

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

                SettingsRow(
                    label   = "Clear All Logs",
                    onClick = { viewModel.clearLogs() },
                )

                SettingsGroup("Log Viewer")
            }

            // Log content — monospace scrollable
            val hScroll = rememberScrollState()
            val vScroll = rememberScrollState()
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
