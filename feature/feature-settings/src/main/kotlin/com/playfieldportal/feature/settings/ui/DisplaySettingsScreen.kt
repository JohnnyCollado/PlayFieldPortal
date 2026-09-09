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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
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
import com.playfieldportal.feature.settings.viewmodel.DisplaySettingsUiState
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

    // Which media row the cursor is on right now — the north/west face-button shortcuts act on
    // it. Every other row clears it (a media row clears itself when it loses focus; the toggles
    // beside them clear it on gain, which covers the gain-before-loss ordering), so the shortcuts
    // are inert everywhere else on this screen.
    var focusedSlot by remember { mutableStateOf<UiMediaSlot?>(null) }

    // Restoring focus after the picker or a reset removes the row's inline action — without this
    // the navigation fallback lands somewhere else entirely. Same machinery as the Sound screen.
    var focusTargetSlot by remember { mutableStateOf<UiMediaSlot?>(null) }
    var focusRequestToken by remember { mutableIntStateOf(0) }
    var importWasActive by remember { mutableStateOf(false) }

    fun requestMediaFocus(slot: UiMediaSlot) {
        focusTargetSlot = slot
        focusRequestToken++
    }

    val wallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.onWallpaperPicked(it) } }

    // ONE picker for every boot/GameBoot media row; the pending slot lives on the ViewModel.
    val uiMediaPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.onUiMediaPicked(uri)
        } else {
            // Cancellation does not change import state, so restore immediately.
            focusTargetSlot?.let(::requestMediaFocus)
        }
    }

    fun pickUiMedia(slot: UiMediaSlot) {
        requestMediaFocus(slot)
        viewModel.onUiMediaPickerLaunchedFor(slot)
        uiMediaPicker.launch(viewModel.uiMediaPickerMime(slot))
    }

    // A rejected import leaves the assignment set unchanged, so "importing went false" is the
    // reliable completion signal rather than waiting for the row's value to differ.
    LaunchedEffect(state.wallpaperImporting) {
        if (state.wallpaperImporting) {
            importWasActive = true
        } else if (importWasActive) {
            importWasActive = false
            focusTargetSlot?.let(::requestMediaFocus)
        }
    }

    fun launchWallpaperPicker() {
        // The wallpaper import shares the importing flag with the media rows, so clear any
        // pending media focus target first: otherwise finishing a wallpaper import would drag
        // the cursor back to whichever media row was picked last.
        focusTargetSlot = null
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
        // Empty, not SettingsDefaultHelperItems: SettingsHelperFooter already falls back with
        // `items.ifEmpty { SettingsDefaultHelperItems }`, so restating the default here would be
        // a second copy of the same rule. Same expression the Sound screen uses.
        helperFooterItems = focusedSlot?.let { slot ->
            MediaRowShortcuts.promptsFor(state.xyLayout, isAssigned = slot.isAssignedIn(state))
        } ?: emptyList(),
        onInterceptAction = { action ->
            // Fullscreen wallpaper preview swallows Confirm/Back — either dismisses it, same
            // as tapping, and the focused row underneath can never be activated through it.
            if (state.wallpaperPreviewVisible) {
                if (action == GamepadAction.SELECT || action == GamepadAction.BACK) {
                    viewModel.hideWallpaperPreview()
                }
                return@SettingsScaffold true
            }
            // North resets the focused media row, west previews it — the same physical buttons
            // doing the same jobs as on the Sound screen. Only consumed over a media row.
            val slot = focusedSlot ?: return@SettingsScaffold false
            when {
                MediaRowShortcuts.isNorthFace(action, state.xyLayout) && slot.isAssignedIn(state) -> {
                    // Advertised only while the row has a custom assignment, so this is consumed
                    // only when it has real work to do. Restore focus after the action vanishes.
                    requestMediaFocus(slot)
                    viewModel.clearUiMedia(slot)
                    true
                }
                MediaRowShortcuts.isWestFace(action, state.xyLayout) -> {
                    when (slot) {
                        UiMediaSlot.BOOT_VIDEO -> onPreviewBootSequence()
                        UiMediaSlot.GAMEBOOT_VIDEO -> onPreviewGameBoot()
                        else -> return@SettingsScaffold false
                    }
                    true
                }
                else -> false
            }
        },
    ) {
        val focusRegistry = LocalSettingsFocusRegistry.current
        LaunchedEffect(focusRequestToken) {
            if (focusRequestToken > 0) {
                // Wait until the removed inline action has left composition and the scaffold has
                // finished its own focus-recovery pass.
                withFrameNanos { }
                withFrameNanos { }
                focusTargetSlot?.let { slot ->
                    runCatching { focusRegistry["display_${slot.key}"]?.requestFocus() }
                }
            }
        }

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
                onFocusChangedExternal = { if (it) focusedSlot = null },
                checked  = state.showBootSequence,
                onToggle = { viewModel.setShowBootSequence(it) },
            )

            SettingsToggleRow(
                label    = "Show Boot Sequence on Resume",
                sublabel = "Also play when returning from a game",
                onFocusChangedExternal = { if (it) focusedSlot = null },
                checked  = state.showBootOnResume,
                onToggle = { viewModel.setShowBootOnResume(it) },
            )

            // ONE field, the same shape as GameBoot below and as every row on the Sound screen:
            // the boot sequence is the built-in logo animation until a clip replaces the whole
            // thing. Boot SOUND is deliberately not here — it is the seventh row of
            // Interface ▸ Sound, which owns every sound in the app.
            MediaAssignmentRow(
                label    = "Boot Video",
                focusKey = "display_${UiMediaSlot.BOOT_VIDEO.key}",
                sublabel = "Play your own video instead of the PFP logo animation " +
                    "(MP4 or WebM, up to 10 seconds)",
                value    = state.bootVideoLabel,
                isAssigned = state.bootVideoAssigned,
                onPick   = { pickUiMedia(UiMediaSlot.BOOT_VIDEO) },
                onPreview = onPreviewBootSequence,
                onUseDefault = { viewModel.clearUiMedia(UiMediaSlot.BOOT_VIDEO) },
                onFocusChanged = { focusedSlot = if (it) UiMediaSlot.BOOT_VIDEO else null },
            )

            SettingsGroup("GameBoot")

            SettingsToggleRow(
                label    = "GameBoot",
                sublabel = "A short presentation between confirming a game and the emulator " +
                    "opening — five seconds built in, up to ten with your own clip — skippable " +
                    "with Confirm or Back.  Off launches straight into the game with the " +
                    "ordinary Launch Sound.",
                onFocusChangedExternal = { if (it) focusedSlot = null },
                checked  = state.gameBootEnabled,
                onToggle = { viewModel.setGameBootEnabled(it) },
            )

            // The field only means anything while GameBoot is on — replacing or previewing a
            // presentation that never plays is a row that lies about what it does.
            if (state.gameBootEnabled) {
                MediaAssignmentRow(
                    label    = "GameBoot Video",
                    focusKey = "display_${UiMediaSlot.GAMEBOOT_VIDEO.key}",
                    sublabel = "Replace the built-in sequence with your own clip, which plays with " +
                        "its own sound — even with Menu Sounds off (MP4 or WebM, up to 10 seconds)",
                    value    = state.gameBootVideoLabel,
                    isAssigned = state.gameBootVideoAssigned,
                    onPick   = { pickUiMedia(UiMediaSlot.GAMEBOOT_VIDEO) },
                    onPreview = onPreviewGameBoot,
                    onUseDefault = { viewModel.clearUiMedia(UiMediaSlot.GAMEBOOT_VIDEO) },
                    onFocusChanged = { focusedSlot = if (it) UiMediaSlot.GAMEBOOT_VIDEO else null },
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

/**
 * Whether [this] media row currently has a custom assignment — what gates the reset shortcut and
 * its prompt. A local extension rather than a state field so the two media rows on this screen
 * cannot answer it differently from the rows themselves.
 */
private fun UiMediaSlot.isAssignedIn(state: DisplaySettingsUiState): Boolean = when (this) {
    UiMediaSlot.BOOT_VIDEO -> state.bootVideoAssigned
    UiMediaSlot.GAMEBOOT_VIDEO -> state.gameBootVideoAssigned
    else -> false
}
