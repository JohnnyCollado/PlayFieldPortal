package com.playfieldportal.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
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
            SettingsGroup("Appearance")

            // Setting a wallpaper automatically replaces the wave; resetting it brings
            // the wave back. No separate mode toggle needed.

            // ── Wallpaper controls ────────────────────────────────────────
            if (state.wallpaperImporting) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 48.dp, vertical = 8.dp),
                )
            } else {
                SettingsRow(
                    label    = "Choose Wallpaper",
                    sublabel = if (state.customWallpaperPath != null) "Custom wallpaper set — replaces the wave"
                               else "Pick an image (PNG, JPG, WEBP) — replaces the wave",
                    onClick  = {
                        wallpaperPicker.launch(
                            arrayOf("image/png", "image/jpeg", "image/webp")
                        )
                    },
                )

                SettingsRow(
                    label    = "Preview Wallpaper",
                    sublabel = "See the selected wallpaper full-screen",
                    onClick  = { viewModel.showWallpaperPreview() },
                )

                if (state.customWallpaperPath != null) {
                    SettingsRow(
                        label    = "Reset Wallpaper",
                        sublabel = "Remove custom wallpaper and restore the default background",
                        onClick  = { viewModel.clearWallpaper() },
                    )
                }
            }

            // ── Wave Style — only relevant when no wallpaper is set ───────
            if (state.customWallpaperPath == null) {
                SettingsValueRow(
                    label    = "Wave Style",
                    sublabel = "Animated   |   Reduced (dimmer, calmer)   |   Static (frozen)   |   Reduced + Static",
                    value    = state.waveStyleLabel,
                    onClick  = { viewModel.cycleWaveStyle() },
                )
            }

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
                sublabel = "PSP Rectangle — horizontal 144:80 icon art   |   Cartridge — game cartridge shape   |   Android apps always use their own icon",
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

            SettingsGroup("Sound")

            SettingsToggleRow(
                label    = "Menu Sounds",
                sublabel = "Play navigation, select, and launch sound effects",
                checked  = state.menuSoundEnabled,
                onToggle = { viewModel.setMenuSoundEnabled(it) },
            )
        }
    }

    if (state.wallpaperPreviewVisible && state.customWallpaperPath != null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { viewModel.hideWallpaperPreview() },
        ) {
            AsyncImage(
                model              = state.customWallpaperPath,
                contentDescription = "Wallpaper preview",
                contentScale       = ContentScale.Fit,
                modifier           = Modifier.fillMaxSize(),
            )
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
