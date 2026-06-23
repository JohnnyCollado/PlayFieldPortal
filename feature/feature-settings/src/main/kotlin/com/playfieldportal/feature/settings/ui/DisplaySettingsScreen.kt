package com.playfieldportal.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.playfieldportal.feature.settings.viewmodel.DisplaySettingsViewModel

@Composable
fun DisplaySettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DisplaySettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    val wallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.onWallpaperPicked(it) } }

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Display",
        onBack   = onBack,
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SettingsGroup("XMB Wave")

            SettingsValueRow(
                label    = "Wave Render Mode",
                sublabel = "Controls animation quality and battery impact",
                value    = state.waveModeName,
                onClick  = { viewModel.cycleWaveMode() },
            )

            SettingsToggleRow(
                label    = "Auto-Reduce on Idle",
                sublabel = "Slow the wave after 3s idle, stop after 10s",
                checked  = state.autoReduceOnIdle,
                onToggle = { viewModel.setAutoReduceOnIdle(it) },
            )

            SettingsGroup("Boot Sequence")

            SettingsToggleRow(
                label    = "Show Boot Sequence",
                sublabel = "PSP-style boot animation on every launch",
                checked  = state.showBootSequence,
                onToggle = { viewModel.setShowBootSequence(it) },
            )

            SettingsToggleRow(
                label    = "Show Boot Sequence on Resume",
                sublabel = "Also play when returning from a game",
                checked  = state.showBootOnResume,
                onToggle = { viewModel.setShowBootOnResume(it) },
            )

            SettingsGroup("Orientation")

            SettingsValueRow(
                label    = "Screen Orientation",
                sublabel = "PFP is designed for landscape use",
                value    = "Landscape (fixed)",
            )

            SettingsGroup("Game Icons")

            SettingsValueRow(
                label    = "Icon Style",
                sublabel = "PSP Rectangle — portrait box art   |   Cartridge — styled chip shape   |   Android apps always use their own icon",
                value    = viewModel.iconStyleLabel(),
                onClick  = { viewModel.cycleIconStyle() },
            )

            SettingsGroup("Performance")

            SettingsToggleRow(
                label    = "Thermal Throttle Awareness",
                sublabel = "Automatically reduce wave quality when device runs hot",
                checked  = state.thermalThrottleAware,
                onToggle = { viewModel.setThermalThrottleAware(it) },
            )

            SettingsToggleRow(
                label    = "Battery Saver Mode",
                sublabel = "Force static wave when Battery Saver is active",
                checked  = state.respectBatterySaver,
                onToggle = { viewModel.setRespectBatterySaver(it) },
            )

            SettingsGroup("Background / Wallpaper")

            SettingsValueRow(
                label    = "Current Wallpaper",
                sublabel = "Displayed behind the XMB interface",
                value    = if (state.customWallpaperPath != null) "Custom" else "Default Theme",
            )

            if (state.wallpaperImporting) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 48.dp, vertical = 8.dp),
                )
            } else {
                SettingsRow(
                    label    = "Choose Custom Wallpaper",
                    sublabel = "PNG, JPG, JPEG, or WEBP",
                    onClick  = {
                        wallpaperPicker.launch(
                            arrayOf("image/png", "image/jpeg", "image/webp")
                        )
                    },
                )

                if (state.customWallpaperPath != null) {
                    SettingsRow(
                        label    = "Reset to Default Wallpaper",
                        sublabel = "Remove custom wallpaper and restore theme background",
                        onClick  = { viewModel.clearWallpaper() },
                    )
                }
            }
        }
    }

    if (state.wallpaperMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissWallpaperMessage() },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissWallpaperMessage() }) {
                    Text("OK")
                }
            },
            text = { Text(state.wallpaperMessage!!) },
        )
    }
}
