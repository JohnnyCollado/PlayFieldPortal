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
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.playfieldportal.core.ui.components.HsvColorPickerDialog
import com.playfieldportal.core.ui.components.hexOf
import com.playfieldportal.core.ui.components.hsvToArgbLong
import com.playfieldportal.core.ui.motion.MotionWallpaperBackground
import com.playfieldportal.core.ui.motion.MotionWallpaperPolicy
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.composite
import com.playfieldportal.core.ui.theme.solveScrimColor
import com.playfieldportal.feature.settings.viewmodel.DisplaySettingsViewModel

@Composable
fun DisplaySettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenXmbLayoutAdjust: () -> Unit = {},
    onOpenCustomIcons: () -> Unit = {},
    onPreviewBootSequence: () -> Unit = {},
    onPreviewGameBoot: () -> Unit = {},
    viewModel: DisplaySettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // Font-colour picker state. Held here rather than in the ViewModel for the same reason the
    // Themes screen holds its icon picker locally: nothing is persisted until Apply.
    var fontPickerOpen by remember { mutableStateOf(false) }
    var pickerHue by remember { mutableFloatStateOf(0f) }
    var pickerSat by remember { mutableFloatStateOf(0f) }
    var pickerVal by remember { mutableFloatStateOf(1f) }
    var pickerChannel by remember { mutableIntStateOf(0) }
    // The "Hidden Items" manager moved to Settings ▸ Library ▸ Hidden Games
    // (settings_app_visibility) — see docs/plans/README.md (Settings hierarchy).

    val wallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.onWallpaperPicked(it) } }

    // ONE picker for all four boot/GameBoot media rows; the pending slot lives on the ViewModel.
    val uiMediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.onUiMediaPicked(it) } }

    fun pickUiMedia(slot: UiMediaSlot) {
        viewModel.onUiMediaPickerLaunchedFor(slot)
        uiMediaPicker.launch(viewModel.uiMediaPickerMime(slot))
    }

    fun launchWallpaperPicker() {
        // ONE picker, not two: the user's mental model is "my background". Still images land on
        // the existing still path; MP4/WebM/GIF route to the motion importer (onWallpaperPicked
        // branches on MIME).
        wallpaperPicker.launch(
            arrayOf(
                "image/png", "image/jpeg", "image/webp",
                "video/mp4", "video/webm", "image/gif",
            )
        )
    }

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Display",
        onBack   = onBack,
        modifier = modifier,
        onInterceptAction = { action ->
            // Fullscreen wallpaper preview swallows Confirm/Back — either dismisses it, same
            // as tapping, and the focused row underneath can never be activated through it.
            if (state.wallpaperPreviewVisible) {
                if (action == GamepadAction.SELECT || action == GamepadAction.BACK) {
                    viewModel.hideWallpaperPreview()
                }
                return@SettingsScaffold true
            }
            false
        },
    ) {
        val scrollState = rememberScrollState()
        LocalSettingsScrollStateRegistrar.current(scrollState)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
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
                    sublabel = if (state.motionWallpaperPath != null) "Motion wallpaper set — a looping video replaces the wave"
                               else if (state.customWallpaperPath != null) "Custom wallpaper set — replaces the wave"
                               else "Pick an image or a short video (PNG, JPG, WEBP, MP4, WEBM, GIF) — replaces the wave",
                    onClick  = ::launchWallpaperPicker,
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

            // ── Wave Style — only relevant when no wallpaper is set. When a MOTION wallpaper
            // is set, the same cycle shows as "Background Motion" (one setting governs "how
            // lively is my background" regardless of which background is active — both write
            // KEY_WAVE_STYLE, so a user who set Static for the wave gets a still poster the
            // moment they pick a video).
            if (state.customWallpaperPath == null) {
                SettingsValueRow(
                    label    = "Wave Style",
                    sublabel = "Animated   |   Reduced (dimmer, calmer)   |   Static (frozen)   |   Reduced + Static",
                    value    = state.waveStyleLabel,
                    onClick  = { viewModel.cycleWaveStyle() },
                )
            } else if (state.motionWallpaperPath != null) {
                SettingsValueRow(
                    label    = "Background Motion",
                    sublabel = "Animated   |   Reduced (slower, calmer)   |   Static (still image)",
                    value    = state.waveStyleLabel,
                    onClick  = { viewModel.cycleWaveStyle() },
                )
            }

            // Icon legibility is an appearance choice, NOT gated on a wallpaper being set —
            // it matters most over a wallpaper, but still applies over the wave.
            SettingsValueRow(
                label    = "Icon Legibility",
                sublabel = "How XMB icons separate from the background.  " +
                    "None  |  Offset Shadow  |  Contour (Dark)  |  Contour (Light)  |  Contour (Auto — follows the icon color)",
                value    = state.iconLegibility.label,
                onClick  = { viewModel.cycleIconLegibility() },
            )

            SettingsToggleRow(
                label    = "Solid Unfocused Icons",
                sublabel = "Draw unselected icons at full opacity — selection still reads by size and label",
                checked  = state.solidUnfocusedIcons,
                onToggle = { viewModel.setSolidUnfocusedIcons(it) },
            )

            // Default on: the shadow is subtle and helper text over bright wallpaper reads far
            // better with it. Users on static dark wallpapers can turn it off.
            SettingsToggleRow(
                label    = "Text Shadow",
                sublabel = "Drop shadow behind row helper text — keeps it readable over bright wallpaper regions",
                checked  = state.textShadow,
                onToggle = { viewModel.setTextShadow(it) },
            )

            // ── Font Colour ──────────────────────────────────────────────────
            // Deliberately next to Text Shadow: the two answer the same question (how does text
            // survive the wallpaper), and AUTO reads the shadow toggle as "may I use a shadow?".
            SettingsValueRow(
                label    = "Font Colour",
                sublabel = "Colour for labels and body text across the interface",
                value    = state.textColorArgb
                    ?.let { hexOf(Color(it and 0xFFFFFFFFL)) }
                    ?: "Theme Default",
                onClick  = {
                    val seed = state.textColorArgb ?: 0xFFFFFFFFL
                    val hsv = FloatArray(3)
                    android.graphics.Color.colorToHSV((seed and 0xFFFFFF).toInt(), hsv)
                    pickerHue = hsv[0]; pickerSat = hsv[1]; pickerVal = hsv[2]
                    pickerChannel = 0
                    fontPickerOpen = true
                },
            )

            if (state.textColorArgb != null) {
                SettingsRow(
                    label    = "Reset Font Colour",
                    sublabel = "Go back to the colour the current theme supplies",
                    onClick  = { viewModel.setTextColor(null) },
                )

                SettingsToggleRow(
                    label    = "Use My Exact Colour",
                    sublabel = "Render the colour exactly as picked. Legibility protection still " +
                        "applies — text may get a shadow or a plate behind it",
                    checked  = state.textColorExact,
                    onToggle = { viewModel.setTextColorExact(it) },
                )
            }

            SettingsValueRow(
                label    = "Text Legibility",
                sublabel = "How text separates from what is behind it.  " +
                    "Automatic  |  None  |  Drop Shadow  |  Outline  |  Contrast Plate",
                value    = state.textLegibility.label,
                onClick  = { viewModel.cycleTextLegibility() },
            )

            SettingsGroup("Scale & Layout")
            Text(
                text     = "Position the XMB live for this screen — scale it, and shift the crossbar " +
                    "up/down and left/right — over the real interface. Each screen size (handheld, " +
                    "foldable, tablet) keeps its own tuning.",
                color    = SettingsSubtext,
                fontSize = 12.sp,
                // Same helper-text shadow as the row family — this paragraph sits directly
                // over the translucent backdrop too.
                style    = androidx.compose.ui.text.TextStyle(shadow = SettingsTextShadow),
                modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
            )

            SettingsRow(
                label    = "Adjust XMB Layout",
                sublabel = "Live editor — scale + reposition the crossbar with the D-pad or sliders",
                onClick  = onOpenXmbLayoutAdjust,
            )

            SettingsRow(
                label    = "Customize XMB Icons",
                sublabel = "Replace any icon with your own image or GIF — live over the XMB",
                onClick  = onOpenCustomIcons,
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

            // Custom boot media. Both are optional and independent: the built-in logo animation
            // and silence are perfectly valid halves, so all four combinations work.
            SettingsValueRow(
                label    = "Boot Animation",
                sublabel = "Play your own video instead of the PFP logo (MP4 or WebM, up to 10 seconds)",
                value    = state.bootVideoLabel,
                onClick  = { pickUiMedia(UiMediaSlot.BOOT_VIDEO) },
            )

            SettingsValueRow(
                label    = "Boot Sound",
                sublabel = "Play your own sound with the boot sequence (MP3, WAV, OGG, or M4A, up to 10 seconds)",
                value    = state.bootAudioLabel,
                onClick  = { pickUiMedia(UiMediaSlot.BOOT_AUDIO) },
            )

            SettingsRow(
                label    = "Preview Boot Sequence",
                sublabel = "Play the boot sequence now, exactly as it plays at startup",
                onClick  = onPreviewBootSequence,
            )

            if (state.bootVideoAssigned || state.bootAudioAssigned) {
                SettingsRow(
                    label    = "Reset Boot Sequence to Default",
                    sublabel = "Remove your boot video and sound, restoring the PFP logo animation",
                    onClick  = { viewModel.resetBootMedia() },
                )
            }

            SettingsGroup("GameBoot")

            SettingsToggleRow(
                label    = "GameBoot",
                sublabel = "Short presentation between confirming a game and the emulator opening. " +
                    "Off by default — turning it on adds a moment to every game launch.",
                checked  = state.gameBootEnabled,
                onToggle = { viewModel.setGameBootEnabled(it) },
            )

            SettingsValueRow(
                label    = "GameBoot Animation",
                sublabel = "Your own video for the transition (MP4 or WebM, up to 5 seconds)",
                value    = state.gameBootVideoLabel,
                onClick  = { pickUiMedia(UiMediaSlot.GAMEBOOT_VIDEO) },
            )

            SettingsValueRow(
                label    = "GameBoot Sound",
                sublabel = "Your own sound for the transition — plays even with Menu Sounds off " +
                    "(MP3, WAV, OGG, or M4A, up to 5 seconds)",
                value    = state.gameBootAudioLabel,
                onClick  = { pickUiMedia(UiMediaSlot.GAMEBOOT_AUDIO) },
            )

            SettingsRow(
                label    = "Preview GameBoot",
                sublabel = "Play the transition now — nothing is launched",
                onClick  = onPreviewGameBoot,
            )

            if (state.gameBootVideoAssigned || state.gameBootAudioAssigned) {
                SettingsRow(
                    label    = "Reset GameBoot to Default",
                    sublabel = "Remove your GameBoot video and sound",
                    onClick  = { viewModel.resetGameBootMedia() },
                )
            }

            SettingsGroup("Orientation")

            SettingsValueRow(
                label    = "Screen Orientation",
                sublabel = "PFP is designed for landscape use",
                value    = "Landscape (fixed)",
            )

            // (The old "Icon Style" option lived here — replaced by Artwork ▸ Game Icon
            // Display, which offers the same cartridge look via Physical Media mode.)

            SettingsGroup("Interface")

            SettingsValueRow(
                label    = "Touch Navigation Button",
                sublabel = "On-screen App Drawer / Back button.  Auto — show only while using touch  |  " +
                    "Always Show  |  Always Hide (controller-only)",
                value    = viewModel.touchNavButtonLabel(),
                onClick  = { viewModel.cycleTouchNavButtonMode() },
            )

            SettingsValueRow(
                label    = "Touch Sensitivity",
                sublabel = "How far a swipe travels per XMB step.  Low — steadier  |  Normal  |  High — faster scrubbing",
                value    = viewModel.touchSensitivityLabel(),
                onClick  = { viewModel.cycleTouchSensitivity() },
            )

            SettingsToggleRow(
                label    = "Context Menu Hint",
                sublabel = "Show the idle “Options” pill over XMB items with a context menu",
                checked  = state.contextMenuHintEnabled,
                onToggle = { viewModel.setContextMenuHintEnabled(it) },
            )

            SettingsSliderRow(
                label     = "Hint Delay",
                sublabel  = "Show after ${formatHintDelay(state.contextMenuHintDelaySeconds)} of inactivity (1–5 seconds)",
                value     = state.contextMenuHintDelaySeconds,
                onValueChange = viewModel::setContextMenuHintDelaySeconds,
                valueRange = 1f..5f,
                steps     = 7,
                enabled  = state.contextMenuHintEnabled,
                valueFormatter = { formatHintDelay(it) },
            )

            SettingsGroup("Performance")

            SettingsToggleRow(
                label    = "Thermal Throttle Awareness",
                sublabel = "Automatically reduce background quality when device runs hot",
                checked  = state.thermalThrottleAware,
                onToggle = { viewModel.setThermalThrottleAware(it) },
            )

            SettingsToggleRow(
                label    = "Battery Saver Mode",
                sublabel = "Freeze the background (wave or motion wallpaper) when Battery Saver is active",
                checked  = state.respectBatterySaver,
                onToggle = { viewModel.setRespectBatterySaver(it) },
            )

            // (The old "Sound" group lived here — Menu Sounds moved to Settings ▸ Interface ▸
            // Audio, which owns the same `sound_menu_enabled` pref plus the per-event sound
            // assignments. No duplicate row may remain.)

            SettingsGroup("Games")

            SettingsToggleRow(
                label    = "Launch Games Directly",
                sublabel = "Confirm starts the game immediately instead of opening Game Details — use \"View Game Details\" in a game's Options menu to edit",
                checked  = state.directLaunch,
                onToggle = { viewModel.setDirectLaunch(it) },
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
            val posterPath = state.customWallpaperPath
            val motionPath = state.motionWallpaperPath
            if (motionPath != null && posterPath != null) {
                // A preview that shows a frozen frame of a video is a bug report waiting to
                // happen — the full-screen preview PLAYS the motion file. The Settings overlay
                // covers the shell, so the shell's own motion decision doesn't apply here; this
                // preview plays unconditionally while visible (it lives and dies with this
                // screen, and dismissing it disposes the player).
                MotionWallpaperBackground(
                    posterPath = posterPath,
                    motionPath = motionPath,
                    decision   = MotionWallpaperPolicy.Decision.PLAY,
                    modifier   = Modifier.fillMaxSize(),
                )
            } else {
                AsyncImage(
                    model              = state.customWallpaperPath,
                    contentDescription = "Wallpaper preview",
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier.fillMaxSize(),
                )
            }
        }
    }

    if (fontPickerOpen) {
        // The strip's two anchors are the real painted backdrop — the solved settings scrim over a
        // worst-case bright wallpaper — so the ratios shown are the ratios the user will get.
        val pfp = LocalPFPColors.current
        val anchors = remember(pfp.backgroundTop, pfp.backgroundBottom) {
            composite(solveScrimColor(pfp.backgroundTop, 0.72f).copy(alpha = 0.72f), Color.White) to
                composite(solveScrimColor(pfp.backgroundBottom, 0.90f).copy(alpha = 0.90f), Color.White)
        }
        HsvColorPickerDialog(
            title           = "Font Colour",
            hue             = pickerHue,
            saturation      = pickerSat,
            brightness      = pickerVal,
            selectedChannel = pickerChannel,
            accent          = SettingsAccent,
            subtext         = SettingsSubtext,
            contrastAnchors = anchors,
            onChannelFraction = { channel, fraction ->
                pickerChannel = channel
                when (channel) {
                    0 -> pickerHue = (fraction * 360f).coerceIn(0f, 360f)
                    1 -> pickerSat = fraction.coerceIn(0f, 1f)
                    else -> pickerVal = fraction.coerceIn(0f, 1f)
                }
            },
            onConfirm = {
                viewModel.setTextColor(hsvToArgbLong(pickerHue, pickerSat, pickerVal))
                fontPickerOpen = false
            },
            onCancel = { fontPickerOpen = false },
        )
    }

    // Three actions, matching the decision: transient dismiss, a permanent opt-out of the clamp,
    // and a permanent opt-out of the notice (adjustment carries on).
    if (state.textContrastNotice != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissTextContrastNotice() },
            title = { Text("Font colour adjusted") },
            text = { Text(state.textContrastNotice!!) },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissTextContrastNotice() }) { Text("OK") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.setTextColorExact(true) }) {
                        Text("Use my exact colour")
                    }
                    TextButton(onClick = { viewModel.suppressTextContrastNotice() }) {
                        Text("Don't warn again")
                    }
                }
            },
        )
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

private fun formatHintDelay(seconds: Float): String =
    if (seconds % 1f == 0f) "${seconds.toInt()}s" else "${seconds}s"
