package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.domain.text.labelRes
import com.playfieldportal.feature.settings.R
import androidx.compose.ui.res.stringResource
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.playfieldportal.core.domain.model.ConfirmBackLayout
import com.playfieldportal.core.domain.model.XYLayout
import com.playfieldportal.feature.settings.viewmodel.ControllerSettingsViewModel

@Composable
fun ControllerSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ControllerSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    SettingsScaffold(
        title    = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.sets_controller_title),
        onBack   = onBack,
        modifier = modifier,
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            SettingsGroup(stringResource(R.string.sets_controller_group_ab))
            Text(
                text     = stringResource(R.string.sets_controller_ab_help),
                color    = SettingsSubtext,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
            )

            SettingsToggleRow(
                label    = stringResource(R.string.tidy_controller_swap_confirm_back),
                sublabel = stringResource(state.layoutPrefs.confirmBackLayout.labelRes),
                checked  = state.layoutPrefs.confirmBackLayout != ConfirmBackLayout.STANDARD,
                onToggle = { viewModel.cycleConfirmBackLayout() },
            )

            SettingsGroup(stringResource(R.string.sets_controller_group_xy))
            Text(
                text     = stringResource(R.string.sets_controller_xy_help),
                color    = SettingsSubtext,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
            )

            SettingsToggleRow(
                label    = stringResource(R.string.tidy_controller_swap_options_sort),
                sublabel = stringResource(state.layoutPrefs.xyLayout.labelRes),
                checked  = state.layoutPrefs.xyLayout != XYLayout.STANDARD,
                onToggle = { viewModel.cycleXYLayout() },
            )

            SettingsGroup(stringResource(R.string.sets_controller_group_type))
            Text(
                text     = stringResource(R.string.sets_controller_type_help),
                color    = SettingsSubtext,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
            )

            SettingsValueRow(
                label    = stringResource(R.string.sets_controller_type),
                sublabel = stringResource(R.string.sets_controller_type_sub),
                value    = stringResource(state.layoutPrefs.displayType.labelRes),
                onClick  = { viewModel.cycleDisplayType() },
            )

            SettingsGroup(stringResource(R.string.sets_controller_group_scroll))
            Text(
                text     = stringResource(R.string.sets_controller_scroll_help),
                color    = SettingsSubtext,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
            )

            SettingsValueRow(
                label    = stringResource(R.string.sets_controller_group_scroll),
                sublabel = stringResource(R.string.sets_controller_scroll_sub),
                value    = stringResource(state.layoutPrefs.scrollSpeed.labelRes),
                onClick  = { viewModel.cycleScrollSpeed() },
            )

            SettingsGroup(stringResource(R.string.sets_controller_group_navigation))
            SettingsToggleRow(
                label    = stringResource(R.string.sets_controller_left_backs_out),
                sublabel = stringResource(R.string.sets_controller_left_backs_out_help),
                checked  = state.layoutPrefs.leftBacksOut,
                onToggle = { viewModel.setLeftBacksOut(it) },
            )

            SettingsGroup(stringResource(R.string.sets_controller_group_keyboard))
            SettingsToggleRow(
                label    = stringResource(R.string.sets_controller_virtual_keyboard),
                sublabel = stringResource(R.string.sets_controller_virtual_keyboard_help),
                checked  = state.layoutPrefs.virtualKeyboard,
                onToggle = { viewModel.setVirtualKeyboard(it) },
            )

            SettingsToggleRow(
                label    = stringResource(R.string.fb_haptic_feedback),
                sublabel = stringResource(R.string.fb_haptic_feedback_help),
                checked  = state.layoutPrefs.hapticFeedback,
                onToggle = { viewModel.setHapticFeedback(it) },
            )

            SettingsGroup(stringResource(R.string.sets_controller_group_reset))
            SettingsRow(
                label    = stringResource(R.string.sets_controller_reset),
                sublabel = stringResource(R.string.sets_controller_reset_help),
                onClick  = { viewModel.resetToDefaults() },
            )
        }
    }
}
