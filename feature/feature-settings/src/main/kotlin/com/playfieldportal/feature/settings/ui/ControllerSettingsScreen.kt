package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.feature.settings.viewmodel.ControllerSettingsViewModel

@Composable
fun ControllerSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ControllerSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // While a remap is in progress, capture every incoming GamepadAction as a button
    // assignment instead of letting it navigate back / move focus / trigger row clicks.
    // Reverse-lookup the current keyCode for that action so we store the raw key, not the
    // logical action. null when not remapping so the scaffold behaves normally.
    val remapInterceptor = remember(state.remappingAction) {
        if (state.remappingAction != null) {
            { action: GamepadAction ->
                val keyCode = viewModel.keycodeForAction(action)
                if (keyCode != null) {
                    viewModel.onKeyPressedDuringRemap(keyCode)
                    true
                } else {
                    false
                }
            }
        } else null
    }

    SettingsScaffold(
        title             = "Settings",
        subtitle          = "Controller",
        onBack            = onBack,
        modifier          = modifier,
        onInterceptAction = remapInterceptor,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            // ── Remapping prompt overlay ──────────────────────────────────
            if (state.remappingAction != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF4A90D9).copy(alpha = 0.15f))
                        .padding(vertical = 16.dp, horizontal = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text       = "Press any button to bind to:",
                            color      = SettingsSubtext,
                            fontSize   = 12.sp,
                        )
                        Text(
                            text       = state.remappingAction!!.name
                                .replace('_', ' ')
                                .lowercase()
                                .replaceFirstChar { it.uppercase() },
                            color      = SettingsAccent,
                            fontSize   = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text    = "(tap this row again to cancel)",
                            color   = SettingsSubtext,
                            fontSize = 11.sp,
                        )
                    }
                }
            }

            SettingsGroup("Button Mappings")
            Text(
                text     = "Tap any row to remap that action to a new button.",
                color    = SettingsSubtext,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
            )

            state.mappings.forEach { row ->
                SettingsRow(
                    label    = row.actionLabel,
                    sublabel = if (row.isRemapping) "▶  Press a button now…" else null,
                    trailing = {
                        Text(
                            text      = if (row.isRemapping) "Listening…" else row.keyLabel,
                            color     = if (row.isRemapping) SettingsAccent else SettingsSubtext,
                            fontSize  = 13.sp,
                            textAlign = TextAlign.End,
                        )
                    },
                    onClick = {
                        if (state.remappingAction == row.action) {
                            viewModel.cancelRemap()
                        } else {
                            viewModel.startRemap(row.action)
                        }
                    },
                )
            }

            SettingsGroup("Repeat Timing")

            SettingsValueRow(
                label    = "Initial Delay",
                sublabel = "Hold time before navigation starts repeating",
                value    = "${state.repeatDelayMs}ms",
            )

            SettingsValueRow(
                label    = "Repeat Rate",
                sublabel = "Interval between repeat firings while held",
                value    = "${state.repeatRateMs}ms",
            )

            SettingsGroup("Reset")

            SettingsRow(
                label    = "Reset All Bindings to Default",
                sublabel = "Restores factory controller mapping",
                onClick  = { viewModel.resetToDefaults() },
            )
        }
    }
}
