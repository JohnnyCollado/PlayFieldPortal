package com.playfieldportal.feature.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.core.domain.model.ConfirmBackLayout
import com.playfieldportal.core.domain.model.XYLayout
import com.playfieldportal.core.domain.model.displayLabel
import com.playfieldportal.feature.settings.viewmodel.ControllerSettingsViewModel

@Composable
fun ControllerSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ControllerSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Controller",
        onBack   = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsGroup("A / B Swap")
            Text(
                text     = "Controls which button confirms and which goes back. " +
                    "Applies globally to all launcher menus.",
                color    = SettingsSubtext,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
            )

            SettingsValueRow(
                label    = "A / B Swap",
                sublabel = state.layoutPrefs.confirmBackLayout.displayLabel(),
                value    = if (state.layoutPrefs.confirmBackLayout == ConfirmBackLayout.STANDARD) {
                    "Off"
                } else {
                    "On"
                },
                onClick  = { viewModel.cycleConfirmBackLayout() },
            )

            SettingsGroup("X / Y Swap")
            Text(
                text     = "Swaps X and Y actions within the launcher UI only. " +
                    "Does not affect emulator controls.",
                color    = SettingsSubtext,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
            )

            SettingsValueRow(
                label    = "X / Y Swap",
                sublabel = state.layoutPrefs.xyLayout.displayLabel(),
                value    = if (state.layoutPrefs.xyLayout == XYLayout.STANDARD) {
                    "Off"
                } else {
                    "On"
                },
                onClick  = { viewModel.cycleXYLayout() },
            )

            SettingsGroup("Controller Type")
            Text(
                text     = "Changes which button icons and labels are shown in help prompts.",
                color    = SettingsSubtext,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
            )

            SettingsValueRow(
                label    = "Type",
                sublabel = "Affects the help bar at the bottom of the launcher",
                value    = state.layoutPrefs.displayType.displayLabel(),
                onClick  = { viewModel.cycleDisplayType() },
            )

            SettingsGroup("Reset")
            SettingsRow(
                label    = "Reset All Controller Settings",
                sublabel = "Restores default swap and type presets",
                onClick  = { viewModel.resetToDefaults() },
            )
        }
    }
}
