package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.ui.components.PfpChoiceOption
import com.playfieldportal.core.ui.components.PfpModalSpec
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import coil3.compose.AsyncImage
import com.playfieldportal.core.ui.components.HsvColorPickerDialog
import com.playfieldportal.core.ui.components.hexOf
import com.playfieldportal.core.ui.components.HsvPickerNav
import com.playfieldportal.core.ui.components.HsvPickerState
import com.playfieldportal.core.ui.motion.MotionWallpaperBackground
import com.playfieldportal.core.ui.motion.MotionWallpaperPolicy
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.UiMediaSlot
import com.playfieldportal.core.domain.text.labelRes
import com.playfieldportal.core.ui.text.asString
import com.playfieldportal.feature.settings.R
import com.playfieldportal.core.ui.sound.LocalMenuSounds
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.composite
import com.playfieldportal.core.ui.theme.solveScrimColor
import com.playfieldportal.feature.settings.viewmodel.DisplaySettingsUiState
import com.playfieldportal.feature.settings.viewmodel.DisplayOption
import com.playfieldportal.feature.settings.viewmodel.DisplaySettingsViewModel
import com.playfieldportal.feature.settings.viewmodel.LockScreenViewModel
import com.playfieldportal.feature.settings.viewmodel.UiMediaRowText

@Composable
fun DisplaySettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenXmbLayoutAdjust: () -> Unit = {},
    onPreviewBootSequence: () -> Unit = {},
    onPreviewGameBoot: () -> Unit = {},
    viewModel: DisplaySettingsViewModel = hiltViewModel(),
    lockScreenViewModel: LockScreenViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    val lockState by lockScreenViewModel.uiState.collectAsState()

    // Font-colour picker state. Held here rather than in the ViewModel for the same reason the
    // Themes screen holds its icon picker locally: nothing is persisted until Apply.
    var fontPicker by remember { mutableStateOf<HsvPickerState?>(null) }
    val fontPickerNav = remember { HsvPickerNav() }
    // One picker serves both rows; this says which colour it is editing.
    var pickingSubFont by remember { mutableStateOf(false) }
    // Biblically Accurate PSP XMB confirmation.
    var pspConfirmOpen by remember { mutableStateOf(false) }
    // The "Hidden Items" manager moved to Settings ▸ Library ▸ Hidden Games
    // (settings_app_visibility).

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

    // The device lock screen takes a still only (see LockScreenImage).
    val lockScreenPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { lockScreenViewModel.onImagePicked(it) } }

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

    // The two modals below swallow input before the scaffold sees it, so they voice their own
    // moves — see the interceptor's own note on why a consumed action cannot be voiced centrally.
    val menuSounds = LocalMenuSounds.current

    val wallpaperMessage = state.wallpaperMessage?.asString()
    val contrastNotice = state.textContrastNotice?.asString()
    // The pick-one row whose choice dialog is open (DisplayOptionChoices.kt).
    var optionPicker by remember { mutableStateOf<DisplayOption?>(null) }
    // Read once into a local, as the two messages above are: a delegated property cannot smart-cast.
    val openOption = optionPicker
    val modal = rememberSettingsModal(
        when {
            pspConfirmOpen -> PfpModalSpec.Confirm(
                key = "psp_layout",
                title = stringResource(R.string.setd_psp_confirm_title),
                message = stringResource(R.string.setd_psp_confirm_message),
                confirmLabel = stringResource(R.string.setd_apply),
                // Opens on Cancel, so a stray double press never replaces a tuned layout.
                openOnCancel = true,
                onConfirm = { viewModel.applyPspLayout(); pspConfirmOpen = false },
                onCancel = { pspConfirmOpen = false },
            )
            wallpaperMessage != null -> PfpModalSpec.Notice(
                key = "wallpaper:$wallpaperMessage",
                title = stringResource(R.string.setd_wallpaper_title),
                message = wallpaperMessage,
                onDismiss = { viewModel.dismissWallpaperMessage() },
            )
            // Low-contrast font colour: a warning, as the shared Choice so a pad can answer it
            // (survey L18, P6). It was a Material dialog with no controller routing.
            contrastNotice != null -> PfpModalSpec.Choice(
                key = "text_contrast:$contrastNotice",
                title = stringResource(R.string.setd_contrast_title),
                message = contrastNotice,
                options = listOf(
                    PfpChoiceOption(stringResource(R.string.setd_contrast_ok)),
                    PfpChoiceOption(stringResource(R.string.setd_contrast_dont_warn)),
                ),
                confirmLabel = stringResource(R.string.setd_select),
                cancelLabel = stringResource(R.string.setd_close),
                onConfirm = { index ->
                    if (index == 1) viewModel.suppressTextContrastNotice() else viewModel.dismissTextContrastNotice()
                },
                onCancel = { viewModel.dismissTextContrastNotice() },
            )
            // A pick-one setting: its values, opened on the one in use. Select stores the pick.
            openOption != null -> PfpModalSpec.Choice(
                key = "option:${openOption.name}",
                title = openOption.title.asString(),
                message = openOption.summary.asString(),
                options = openOption.choices.map { PfpChoiceOption(it.label.asString(), it.detail?.asString()) },
                confirmLabel = stringResource(R.string.setd_select),
                initialSelected = viewModel.selectedChoice(openOption),
                onConfirm = { index -> viewModel.choose(openOption, index); optionPicker = null },
                onCancel = { optionPicker = null },
            )
            else -> null
        },
    )

    SettingsScaffold(
        title    = stringResource(R.string.settings_title),
        subtitle = stringResource(R.string.setd_display_title),
        modalOpen = modal.open || fontPicker != null,
        onBack   = onBack,
        modifier = modifier,
        // Empty, not SettingsDefaultHelperItems: SettingsHelperFooter already falls back with
        // `items.ifEmpty { SettingsDefaultHelperItems }`, so restating the default here would be
        // a second copy of the same rule. Same expression the Sound screen uses.
        helperFooterItems = focusedSlot?.let { slot ->
            MediaRowShortcuts.promptsFor(state.xyLayout, isAssigned = slot.isAssignedIn(state))
        } ?: emptyList(),
        onInterceptAction = { action ->
            // A modal is a hard input boundary: nothing behind it sees a press.
            if (modal.intercept(action)) return@SettingsScaffold true
            // The font-colour picker is a modal too: its own controller model takes every press.
            fontPicker?.let { open ->
                var closed = false
                val next = fontPickerNav.handle(
                    state = open,
                    action = action,
                    sounds = menuSounds,
                    onApply = { argb ->
                        closed = true
                        if (pickingSubFont) viewModel.setSubTextColor(argb) else viewModel.setTextColor(argb)
                    },
                    onCancel = { closed = true },
                )
                fontPicker = if (closed) null else next
                return@SettingsScaffold true
            }
            // Fullscreen wallpaper preview swallows Confirm/Back — either dismisses it, same
            // as tapping, and the focused row underneath can never be activated through it.
            if (state.wallpaperPreviewVisible) {
                if (action == GamepadAction.SELECT || action == GamepadAction.BACK) {
                    // Both buttons dismiss, so both are a back regardless of which one was pressed.
                    menuSounds.play(MenuSound.BACK)
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
                    // Same cue the row's own Use Default action plays — one shortcut, one meaning.
                    menuSounds.play(MenuSound.CONFIRM)
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
            // One setting above every other motion choice (it was Artwork ▸ Animated images, and
            // only covered images). Static holds the wave, a motion wallpaper, video snaps and the
            // glow's pulse still; the rows it overrides say so.
            val motionStatic = state.motion == com.playfieldportal.core.domain.model.ImageMotion.STATIC
            SettingsGroup(stringResource(R.string.motion_title))
            SettingsValueRow(
                label    = DisplayOption.MOTION.title.asString(),
                sublabel = stringResource(
                    when (state.motion) {
                        com.playfieldportal.core.domain.model.ImageMotion.ANIMATED -> R.string.motion_sub_animated
                        com.playfieldportal.core.domain.model.ImageMotion.REDUCED -> R.string.motion_sub_reduced
                        com.playfieldportal.core.domain.model.ImageMotion.STATIC -> R.string.motion_sub_static
                    },
                ),
                value    = stringResource(state.motion.labelRes),
                onClick  = { optionPicker = DisplayOption.MOTION },
                opensPicker = true,
            )

            SettingsGroup(stringResource(R.string.setd_group_appearance))

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
                    label    = stringResource(R.string.setd_wallpaper_choose),
                    sublabel = if (state.motionWallpaperPath != null) stringResource(R.string.setd_wallpaper_choose_motion_set)
                               else if (state.customWallpaperPath != null) stringResource(R.string.setd_wallpaper_choose_custom_set)
                               else stringResource(R.string.setd_wallpaper_choose_none),
                    onClick  = ::launchWallpaperPicker,
                )

                // Both rows act on a picked wallpaper, so both wait for one; with none, Preview
                // only said "No wallpaper selected yet" (survey U31).
                if (state.customWallpaperPath != null) {
                    SettingsRow(
                        label    = stringResource(R.string.setd_wallpaper_preview),
                        sublabel = stringResource(R.string.setd_wallpaper_preview_sublabel),
                        onClick  = { viewModel.showWallpaperPreview() },
                    )
                    SettingsRow(
                        label    = stringResource(R.string.setd_wallpaper_reset_row),
                        sublabel = stringResource(R.string.setd_wallpaper_reset_row_sublabel),
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
                    label    = DisplayOption.WAVE_STYLE.title.asString(),
                    sublabel = if (motionStatic) stringResource(R.string.motion_holds_background)
                    else DisplayOption.WAVE_STYLE.summary.asString(),
                    value    = state.waveStyleLabel.asString(),
                    onClick  = { optionPicker = DisplayOption.WAVE_STYLE },
                    opensPicker = true,
                )
            } else if (state.motionWallpaperPath != null) {
                SettingsValueRow(
                    label    = DisplayOption.BACKGROUND_MOTION.title.asString(),
                    sublabel = if (motionStatic) stringResource(R.string.motion_holds_background)
                    else DisplayOption.BACKGROUND_MOTION.summary.asString(),
                    value    = state.waveStyleLabel.asString(),
                    onClick  = { optionPicker = DisplayOption.BACKGROUND_MOTION },
                    opensPicker = true,
                )
            }

            // ── Lock screen image — the DEVICE lock screen, a still only ──────────
            SettingsGroup(stringResource(R.string.setd_group_lock_screen))
            SettingsRow(
                label    = stringResource(R.string.setd_lock_choose),
                sublabel = when {
                    lockState.busy -> stringResource(R.string.setd_lock_choose_busy)
                    lockState.fromTheme -> stringResource(R.string.setd_lock_choose_from_theme)
                    lockState.isSet -> stringResource(R.string.setd_lock_choose_set)
                    else -> stringResource(R.string.setd_lock_choose_none)
                },
                onClick  = if (lockState.busy) null else ({ lockScreenPicker.launch(arrayOf("image/png", "image/jpeg", "image/webp")) }),
            )
            if (lockState.hasLauncherWallpaper) {
                SettingsRow(
                    label    = stringResource(R.string.setd_lock_use_wallpaper),
                    sublabel = if (state.motionWallpaperPath != null) stringResource(R.string.setd_lock_use_wallpaper_motion)
                               else stringResource(R.string.setd_lock_use_wallpaper_still),
                    onClick  = if (lockState.busy) null else ({ lockScreenViewModel.useLauncherWallpaper() }),
                )
            }
            if (lockState.isSet) {
                SettingsRow(
                    label    = stringResource(R.string.setd_lock_reset_row),
                    sublabel = stringResource(R.string.setd_lock_reset_row_sublabel),
                    onClick  = if (lockState.busy) null else ({ lockScreenViewModel.reset() }),
                )
            }

            SettingsGroup(stringResource(R.string.tidy_group_text_icons))
            // Icon legibility is an appearance choice, NOT gated on a wallpaper being set —
            // it matters most over a wallpaper, but still applies over the wave.
            SettingsValueRow(
                label    = DisplayOption.ICON_LEGIBILITY.title.asString(),
                sublabel = DisplayOption.ICON_LEGIBILITY.summary.asString(),
                value    = stringResource(state.iconLegibility.labelRes),
                onClick  = { optionPicker = DisplayOption.ICON_LEGIBILITY },
                opensPicker = true,
            )

            SettingsToggleRow(
                label    = stringResource(R.string.setd_solid_icons),
                sublabel = stringResource(R.string.setd_solid_icons_sublabel),
                checked  = state.solidUnfocusedIcons,
                onToggle = { viewModel.setSolidUnfocusedIcons(it) },
            )

            // Default on: the shadow is subtle and helper text over bright wallpaper reads far
            // better with it. Users on static dark wallpapers can turn it off.
            SettingsToggleRow(
                label    = stringResource(R.string.setd_text_shadow),
                sublabel = stringResource(R.string.setd_text_shadow_sublabel),
                checked  = state.textShadow,
                onToggle = { viewModel.setTextShadow(it) },
            )

            SettingsValueRow(
                label    = DisplayOption.ITEM_LIST_MOTION.title.asString(),
                sublabel = DisplayOption.ITEM_LIST_MOTION.summary.asString(),
                value    = stringResource(state.itemListMotion.labelRes),
                onClick  = { optionPicker = DisplayOption.ITEM_LIST_MOTION },
                opensPicker = true,
            )

            SettingsValueRow(
                label    = DisplayOption.UMD_SLOT.title.asString(),
                sublabel = DisplayOption.UMD_SLOT.summary.asString(),
                value    = stringResource(state.umdSlotMode.labelRes),
                onClick  = { optionPicker = DisplayOption.UMD_SLOT },
                opensPicker = true,
            )

            // ── Font Colour ──────────────────────────────────────────────────
            // Deliberately next to Text Shadow: the two answer the same question (how does text
            // survive the wallpaper), and AUTO reads the shadow toggle as "may I use a shadow?".
            SettingsValueRow(
                label    = stringResource(R.string.setd_font_colour),
                sublabel = stringResource(R.string.setd_font_colour_sublabel),
                value    = state.textColorArgb
                    ?.let { hexOf(Color(it and 0xFFFFFFFFL)) }
                    ?: stringResource(R.string.setd_font_colour_theme_default),
                onClick  = {
                    pickingSubFont = false
                    fontPicker = HsvPickerState.fromArgb(state.textColorArgb ?: 0xFFFFFFFFL)
                },
            )

            SettingsValueRow(
                label    = stringResource(R.string.setd_sub_font_colour),
                sublabel = stringResource(R.string.setd_sub_font_colour_sublabel),
                value    = state.subTextColorArgb
                    ?.let { hexOf(Color(it and 0xFFFFFFFFL)) }
                    ?: stringResource(R.string.setd_sub_font_colour_same),
                onClick  = {
                    pickingSubFont = true
                    fontPicker = HsvPickerState.fromArgb(state.subTextColorArgb ?: state.textColorArgb ?: 0xFFFFFFFFL)
                },
            )

            if (state.subTextColorArgb != null) {
                SettingsRow(
                    label    = stringResource(R.string.setd_sub_font_colour_reset),
                    sublabel = stringResource(R.string.setd_sub_font_colour_reset_sublabel),
                    onClick  = { viewModel.setSubTextColor(null) },
                )
            }

            if (state.textColorArgb != null) {
                SettingsRow(
                    label    = stringResource(R.string.setd_font_colour_reset),
                    sublabel = stringResource(R.string.setd_font_colour_reset_sublabel),
                    onClick  = { viewModel.setTextColor(null) },
                )
            }

            // Honest copy: the choice is stored and travels with themes and backups, but no
            // renderer reads it yet (survey U2, docs/audits/codebase-survey-2026-10-22.md).
            SettingsValueRow(
                label    = DisplayOption.TEXT_LEGIBILITY.title.asString(),
                sublabel = DisplayOption.TEXT_LEGIBILITY.summary.asString(),
                value    = stringResource(state.textLegibility.labelRes),
                onClick  = { optionPicker = DisplayOption.TEXT_LEGIBILITY },
                opensPicker = true,
            )

            SettingsGroup(stringResource(R.string.setd_group_xmb_layout))
            Text(
                text     = stringResource(R.string.setd_xmb_layout_intro),
                color    = SettingsSubtext,
                fontSize = 12.sp,
                // Same helper-text shadow as the row family — this paragraph sits directly
                // over the translucent backdrop too.
                style    = androidx.compose.ui.text.TextStyle(shadow = SettingsTextShadow),
                modifier = Modifier.padding(horizontal = 48.dp, vertical = 4.dp),
            )

            SettingsRow(
                label    = stringResource(R.string.setd_xmb_adjust),
                sublabel = stringResource(R.string.setd_xmb_adjust_sublabel),
                onClick  = onOpenXmbLayoutAdjust,
            )

            // Greyed out while this screen size's saved layout IS the preset; any change saved from
            // the editor above, a reset to default included, brings it back.
            SettingsRow(
                label    = stringResource(R.string.setd_psp_row),
                sublabel = if (state.pspLayoutApplied) {
                    stringResource(R.string.setd_psp_row_applied)
                } else {
                    stringResource(R.string.setd_psp_row_apply)
                },
                enabled  = !state.pspLayoutApplied,
                onClick  = { pspConfirmOpen = true },
            )

            SettingsGroup(stringResource(R.string.setd_group_boot_sequence))

            SettingsToggleRow(
                label    = stringResource(R.string.setd_boot_show),
                sublabel = stringResource(R.string.setd_boot_show_sublabel),
                onFocusChangedExternal = { if (it) focusedSlot = null },
                checked  = state.showBootSequence,
                onToggle = { viewModel.setShowBootSequence(it) },
            )

            SettingsToggleRow(
                label    = stringResource(R.string.setd_boot_resume),
                sublabel = stringResource(R.string.setd_boot_resume_sublabel),
                onFocusChangedExternal = { if (it) focusedSlot = null },
                checked  = state.showBootOnResume,
                onToggle = { viewModel.setShowBootOnResume(it) },
            )

            // ONE field, the same shape as GameBoot below and as every row on the Sound screen:
            // the boot sequence is the built-in logo animation until a clip replaces the whole
            // thing. Boot SOUND is deliberately not here — it is the seventh row of
            // Interface ▸ Sound, which owns every sound in the app.
            MediaAssignmentRow(
                label    = stringResource(R.string.setd_boot_video),
                focusKey = "display_${UiMediaSlot.BOOT_VIDEO.key}",
                sublabel = stringResource(
                    R.string.setd_boot_video_sublabel,
                    UiMediaRowText.videoCap(UiMediaSlot.BOOT_VIDEO).asString(),
                ),
                value    = state.bootVideoLabel.asString(),
                isAssigned = state.bootVideoAssigned,
                onPick   = { pickUiMedia(UiMediaSlot.BOOT_VIDEO) },
                onPreview = onPreviewBootSequence,
                onUseDefault = { viewModel.clearUiMedia(UiMediaSlot.BOOT_VIDEO) },
                onFocusChanged = { focusedSlot = if (it) UiMediaSlot.BOOT_VIDEO else null },
            )

            SettingsGroup(stringResource(R.string.setd_group_game_boot))

            SettingsToggleRow(
                label    = stringResource(R.string.setd_gameboot),
                sublabel = stringResource(R.string.setd_gameboot_sublabel),
                onFocusChangedExternal = { if (it) focusedSlot = null },
                checked  = state.gameBootEnabled,
                onToggle = { viewModel.setGameBootEnabled(it) },
            )

            // The field only means anything while GameBoot is on — replacing or previewing a
            // presentation that never plays is a row that lies about what it does.
            if (state.gameBootEnabled) {
                MediaAssignmentRow(
                    label    = stringResource(R.string.setd_gameboot_video),
                    focusKey = "display_${UiMediaSlot.GAMEBOOT_VIDEO.key}",
                    sublabel = stringResource(
                        R.string.setd_gameboot_video_sublabel,
                        UiMediaRowText.videoCap(UiMediaSlot.GAMEBOOT_VIDEO).asString(),
                    ),
                    value    = state.gameBootVideoLabel.asString(),
                    isAssigned = state.gameBootVideoAssigned,
                    onPick   = { pickUiMedia(UiMediaSlot.GAMEBOOT_VIDEO) },
                    onPreview = onPreviewGameBoot,
                    onUseDefault = { viewModel.clearUiMedia(UiMediaSlot.GAMEBOOT_VIDEO) },
                    onFocusChanged = { focusedSlot = if (it) UiMediaSlot.GAMEBOOT_VIDEO else null },
                )
            }

            SettingsGroup(stringResource(R.string.setd_group_orientation))

            SettingsValueRow(
                label    = DisplayOption.SCREEN_ORIENTATION.title.asString(),
                sublabel = DisplayOption.SCREEN_ORIENTATION.summary.asString(),
                value    = viewModel.screenOrientationLabel().asString(),
                onClick  = { optionPicker = DisplayOption.SCREEN_ORIENTATION },
                opensPicker = true,
            )

            // (The old "Icon Style" option lived here — replaced by Artwork ▸ Game Icon
            // Display, which offers the same cartridge look via Physical Media mode.)

            SettingsGroup(stringResource(R.string.setd_group_interface))

            SettingsValueRow(
                label    = DisplayOption.TOUCH_NAV_BUTTON.title.asString(),
                sublabel = DisplayOption.TOUCH_NAV_BUTTON.summary.asString(),
                value    = viewModel.touchNavButtonLabel().asString(),
                onClick  = { optionPicker = DisplayOption.TOUCH_NAV_BUTTON },
                opensPicker = true,
            )

            SettingsValueRow(
                label    = DisplayOption.TOUCH_SENSITIVITY.title.asString(),
                sublabel = DisplayOption.TOUCH_SENSITIVITY.summary.asString(),
                value    = viewModel.touchSensitivityLabel().asString(),
                onClick  = { optionPicker = DisplayOption.TOUCH_SENSITIVITY },
                opensPicker = true,
            )

            SettingsToggleRow(
                label    = stringResource(SettingsLabels.OPTIONS_HINT_RES),
                sublabel = stringResource(R.string.setd_options_hint_sublabel),
                checked  = state.contextMenuHintEnabled,
                onToggle = { viewModel.setContextMenuHintEnabled(it) },
            )

            SettingsSliderRow(
                label     = stringResource(R.string.setd_hint_delay),
                sublabel  = stringResource(R.string.setd_hint_delay_sublabel, formatHintDelay(state.contextMenuHintDelaySeconds)),
                value     = state.contextMenuHintDelaySeconds,
                onValueChange = viewModel::setContextMenuHintDelaySeconds,
                valueRange = 1f..5f,
                steps     = 7,
                enabled  = state.contextMenuHintEnabled,
                valueFormatter = { formatHintDelay(it) },
            )

            // The light round the focused icon. Pulsing says when it will hold still (Animated
            // Images: Static).
            SettingsValueRow(
                label    = stringResource(R.string.glow_title),
                sublabel = stringResource(
                    when (state.focusGlow) {
                        com.playfieldportal.core.domain.model.FocusGlowMode.OFF -> R.string.glow_sub_off
                        com.playfieldportal.core.domain.model.FocusGlowMode.STEADY -> R.string.glow_sub_steady
                        com.playfieldportal.core.domain.model.FocusGlowMode.PULSING ->
                            if (motionStatic) R.string.motion_holds_glow else R.string.glow_sub_pulsing
                    },
                ),
                value    = stringResource(
                    when (state.focusGlow) {
                        com.playfieldportal.core.domain.model.FocusGlowMode.OFF -> R.string.glow_value_off
                        com.playfieldportal.core.domain.model.FocusGlowMode.STEADY -> R.string.glow_value_steady
                        com.playfieldportal.core.domain.model.FocusGlowMode.PULSING -> R.string.glow_value_pulsing
                    },
                ),
                onClick  = { viewModel.cycleFocusGlow() },
            )

            SettingsGroup(stringResource(R.string.setd_group_performance))

            // The blur drawn behind prompts and the options panel. Its maximum is the strength the
            // feature first shipped with; this row can only weaken it, down to off. Android 12+ only.
            val blurAvailable = android.os.Build.VERSION.SDK_INT >= 31
            SettingsSliderRow(
                label     = stringResource(R.string.blur_title),
                sublabel  = stringResource(if (blurAvailable) R.string.blur_sublabel else R.string.blur_unavailable),
                value     = state.backdropBlurStrength,
                onValueChange = viewModel::setBackdropBlurStrength,
                valueRange = 0f..1f,
                steps     = com.playfieldportal.core.domain.model.BackdropBlur.SLIDER_STEPS,
                enabled   = blurAvailable,
                valueFormatter = { "${Math.round(it * 100)}%" },
            )

            SettingsToggleRow(
                label    = stringResource(R.string.setd_thermal),
                sublabel = stringResource(R.string.setd_thermal_sublabel),
                checked  = state.thermalThrottleAware,
                onToggle = { viewModel.setThermalThrottleAware(it) },
            )

            SettingsToggleRow(
                label    = stringResource(R.string.setd_battery_saver),
                sublabel = stringResource(R.string.setd_battery_saver_sublabel),
                checked  = state.respectBatterySaver,
                onToggle = { viewModel.setRespectBatterySaver(it) },
            )

            // (The old "Sound" group lived here — it moved to Settings ▸ Interface ▸ Sound, which
            // owns master volume, the per-sound levels and the sound assignments. The Menu Sounds
            // toggle it used to hold is gone entirely: master at 0 is the mute. No duplicate row
            // may remain.)

            SettingsGroup(stringResource(R.string.setd_group_games))

            SettingsToggleRow(
                label    = stringResource(R.string.setd_direct_launch),
                sublabel = stringResource(R.string.setd_direct_launch_sublabel),
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
                // happen — the full-screen preview PLAYS the motion file. It plays while visible
                // (it lives and dies with this screen, and dismissing it disposes the player), and
                // while it is up it covers the shell's own wallpaper so only one decoder runs.
                androidx.compose.runtime.DisposableEffect(Unit) {
                    val cover = com.playfieldportal.core.ui.motion.MotionCover.Shared.hold()
                    onDispose { cover.release() }
                }
                MotionWallpaperBackground(
                    posterPath = posterPath,
                    motionPath = motionPath,
                    decision   = MotionWallpaperPolicy.Decision.PLAY,
                    modifier   = Modifier.fillMaxSize(),
                )
            } else {
                AsyncImage(
                    model              = state.customWallpaperPath,
                    contentDescription = stringResource(R.string.setd_wallpaper_preview_cd),
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier.fillMaxSize(),
                )
            }
        }
    }

    fontPicker?.let { open ->
        // The strip's two anchors are the real painted backdrop — the solved settings scrim over a
        // worst-case bright wallpaper — so the ratios shown are the ratios the user will get.
        val pfp = LocalPFPColors.current
        val anchors = remember(pfp.backgroundTop, pfp.backgroundBottom) {
            composite(solveScrimColor(pfp.backgroundTop, 0.72f).copy(alpha = 0.72f), Color.White) to
                composite(solveScrimColor(pfp.backgroundBottom, 0.90f).copy(alpha = 0.90f), Color.White)
        }
        HsvColorPickerDialog(
            title           = stringResource(if (pickingSubFont) R.string.setd_picker_sub_font_title else R.string.setd_picker_font_title),
            state           = open,
            onStateChange   = { fontPicker = it },
            accent          = SettingsAccent,
            subtext         = SettingsSubtext,
            contrastAnchors = anchors,
            onConfirm = {
                if (pickingSubFont) viewModel.setSubTextColor(open.argb) else viewModel.setTextColor(open.argb)
                fontPicker = null
            },
            onCancel = { fontPicker = null },
            showHints = LocalSettingsShowControllerHint.current,
        )
    }

    modal.Content()

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
