package com.playfieldportal.feature.settings.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.PspContextMenuOverlay
import com.playfieldportal.core.ui.components.PspMenuRow
import com.playfieldportal.core.data.repository.PfpThemeStore
import com.playfieldportal.feature.settings.viewmodel.ThemesSettingsViewModel

@Composable
fun ThemesSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenColorSchemePicker: () -> Unit = {},
    viewModel: ThemesSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()

    // SAF picker for official PSP .ptf themes (no registered MIME type, so accept any file;
    // the importer validates the magic and rejects non-PTF with a clear message).
    val ptfPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importPtfTheme(it) } }

    // Quick Create: pick any photo — it becomes a saved theme with its color auto-derived.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.createThemeFromPhoto(it) } }

    // Shared .pfptheme bundles (no registered MIME; the codec validates the contents).
    val pfpPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.importPfpTheme(it) } }

    // ── Controller context-menu state ─────────────────────────────────────────
    // Workflow: hover a theme (card strip or installed row) → options button (Y/△) → menu.
    var menu by remember { mutableStateOf<ThemeMenu?>(null) }
    var menuIndex by remember { mutableStateOf(0) }
    // My Themes card strip: whether the strip holds focus, and which card is selected in it.
    var myThemesFocused by remember { mutableStateOf(false) }
    var cardIndex by remember { mutableStateOf(0) }
    // Icon color strip: whether it holds focus, and which swatch is selected (the last index is
    // the "Custom" swatch, which opens the HSV picker below).
    var iconStripFocused by remember { mutableStateOf(false) }
    var iconIndex by remember { mutableStateOf(0) }
    // Custom icon-color picker (HSV). Open state plus the live channel values it edits.
    var customPicker by remember { mutableStateOf(false) }
    var pickerHue by remember { mutableStateOf(0f) }        // 0..360
    var pickerSat by remember { mutableStateOf(0f) }        // 0..1
    var pickerVal by remember { mutableStateOf(1f) }        // 0..1
    var pickerChannel by remember { mutableStateOf(0) }     // 0=hue, 1=saturation, 2=brightness
    val customIndex = IconColorChoices.size

    // Seeds the picker from the current icon color (or white) and opens it.
    fun openIconPicker() {
        val argb = state.iconColorArgb ?: 0xFFFFFFFFL
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV((argb and 0xFFFFFFFFL).toInt(), hsv)
        pickerHue = hsv[0]; pickerSat = hsv[1]; pickerVal = hsv[2]; pickerChannel = 0
        customPicker = true
    }

    fun openMenuForSavedTheme(theme: PfpThemeStore.SavedTheme) {
        menuIndex = 0
        menu = ThemeMenu(theme.name, listOf(
            ThemeMenuOption("Apply")  { viewModel.applySavedTheme(theme.id) },
            ThemeMenuOption("Share")  { viewModel.shareSavedTheme(theme.id) },
            ThemeMenuOption("Remove", destructive = true) { viewModel.deleteSavedTheme(theme.id) },
        ))
    }

    Box(modifier = modifier) {

    SettingsScaffold(
        title    = "Settings",
        subtitle = "Themes",
        onBack   = onBack,
        modifier = Modifier.fillMaxSize(),
        onInterceptAction = { action ->
            val m = menu
            when {
                // The custom color picker captures ALL input while open.
                customPicker -> {
                    when (action) {
                        GamepadAction.NAVIGATE_UP   -> pickerChannel = (pickerChannel + 2) % 3
                        GamepadAction.NAVIGATE_DOWN -> pickerChannel = (pickerChannel + 1) % 3
                        GamepadAction.NAVIGATE_LEFT -> when (pickerChannel) {
                            0 -> pickerHue = ((pickerHue - 6f) % 360f + 360f) % 360f
                            1 -> pickerSat = (pickerSat - 0.04f).coerceIn(0f, 1f)
                            else -> pickerVal = (pickerVal - 0.04f).coerceIn(0f, 1f)
                        }
                        GamepadAction.NAVIGATE_RIGHT -> when (pickerChannel) {
                            0 -> pickerHue = ((pickerHue + 6f) % 360f + 360f) % 360f
                            1 -> pickerSat = (pickerSat + 0.04f).coerceIn(0f, 1f)
                            else -> pickerVal = (pickerVal + 0.04f).coerceIn(0f, 1f)
                        }
                        GamepadAction.SELECT -> {
                            viewModel.setIconColor(hsvToArgbLong(pickerHue, pickerSat, pickerVal))
                            customPicker = false
                        }
                        GamepadAction.BACK -> customPicker = false
                        else -> Unit
                    }
                    true
                }
                // Open menu captures ALL input while visible.
                m != null -> {
                    when (action) {
                        GamepadAction.NAVIGATE_UP   -> menuIndex = (menuIndex - 1).coerceAtLeast(0)
                        GamepadAction.NAVIGATE_DOWN -> menuIndex = (menuIndex + 1).coerceAtMost(m.options.size - 1)
                        GamepadAction.SELECT        -> { m.options.getOrNull(menuIndex)?.action?.invoke(); menu = null }
                        GamepadAction.BACK,
                        GamepadAction.LONG_PRESS,
                        GamepadAction.BUTTON_Y      -> menu = null
                        else -> Unit
                    }
                    true
                }
                // Left/Right browse the My Themes card strip while it holds focus.
                myThemesFocused && action == GamepadAction.NAVIGATE_LEFT -> {
                    cardIndex = (cardIndex - 1).coerceAtLeast(0); true
                }
                myThemesFocused && action == GamepadAction.NAVIGATE_RIGHT -> {
                    cardIndex = (cardIndex + 1).coerceAtMost((state.savedThemes.size - 1).coerceAtLeast(0)); true
                }
                // Left/Right browse the icon color swatches while that strip holds focus (the last
                // index is the Custom swatch; Confirm on it opens the HSV picker).
                iconStripFocused && action == GamepadAction.NAVIGATE_LEFT -> {
                    iconIndex = (iconIndex - 1).coerceAtLeast(0); true
                }
                iconStripFocused && action == GamepadAction.NAVIGATE_RIGHT -> {
                    iconIndex = (iconIndex + 1).coerceAtMost(customIndex); true
                }
                // Options button (LONG_PRESS or BUTTON_Y, mapping-dependent) on a hovered
                // theme card opens its context menu.
                action == GamepadAction.LONG_PRESS || action == GamepadAction.BUTTON_Y -> {
                    if (myThemesFocused) {
                        state.savedThemes.getOrNull(cardIndex)?.let { openMenuForSavedTheme(it) }
                    }
                    true
                }
                else -> false
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {

            // ── Appearance ────────────────────────────────────────────────
            SettingsGroup("Appearance")

            // Opens a PSP-style submenu over the live XMB for picking & previewing
            // the background color scheme.
            SettingsRow(
                label    = "Color Scheme",
                sublabel = if (state.accentOverrideArgb != null) {
                    "Custom theme color active — picking a preset replaces it"
                } else "PSP-style background colors — preview live",
                onClick  = onOpenColorSchemePicker,
            )

            state.accentOverrideArgb?.let { accent ->
                SettingsRow(
                    label    = "Custom Theme Color",
                    sublabel = "From an imported theme — tap to remove and return to the color scheme",
                    onClick  = { viewModel.clearAccentOverride() },
                    trailing = { ColorDot(argb = accent) },
                )
            }

            // Unified icon color: one tint across the XMB's icon set (docs/icon-system-plan.md).
            // One focusable stop for the whole strip: Up/Down reach it like a row, Left/Right pick
            // a swatch, A applies (or opens the Custom HSV picker on the last swatch).
            SettingsGroup("Icon Color")
            FocusableStrip(
                onFocusChange = { focused ->
                    iconStripFocused = focused
                    if (focused) iconIndex = iconIndex.coerceIn(0, customIndex)
                },
                onSelect = {
                    if (iconIndex == customIndex) openIconPicker()
                    else viewModel.setIconColor(IconColorChoices[iconIndex].second)
                },
            ) { stripFocused ->
                IconColorSwatchRow(
                    selectedArgb   = state.iconColorArgb,
                    focusedIndex   = if (stripFocused) iconIndex else null,
                    onSelectPreset = { viewModel.setIconColor(it) },
                    onSelectCustom = { openIconPicker() },
                )
            }

            // ── Saved themes (Quick Create + PSP imports) ────────────────────
            SettingsGroup("My Themes")

            SettingsRow(
                label    = "New Theme from Photo",
                sublabel = "Pick a picture — wallpaper and color are set from it",
                onClick  = if (state.isInstalling) null else ({ photoPicker.launch(arrayOf("image/*")) }),
            )

            if (state.savedThemes.isNotEmpty()) {
                // One focusable container for the whole strip: Up/Down reach it like a row,
                // Left/Right browse cards, A applies, Y/long-press opens Share/Remove.
                FocusableStrip(
                    onFocusChange = { focused ->
                        myThemesFocused = focused
                        if (focused) cardIndex = cardIndex.coerceIn(0, state.savedThemes.size - 1)
                    },
                    onSelect = {
                        state.savedThemes.getOrNull(cardIndex)?.let { viewModel.applySavedTheme(it.id) }
                    },
                ) { stripFocused ->
                    SavedThemeCardRow(
                        themes       = state.savedThemes,
                        focusedIndex = if (stripFocused) cardIndex else null,
                        onApply      = { viewModel.applySavedTheme(it) },
                        onDelete     = { viewModel.deleteSavedTheme(it) },
                        onShare      = { viewModel.shareSavedTheme(it) },
                    )
                }
            }

            // ── Active theme ──────────────────────────────────────────────
            SettingsGroup("Active Theme")

            SettingsValueRow(
                label = "Current Theme",
                value = state.activeThemeName,
            )

            SettingsRow(
                label    = "Reset to Default",
                sublabel = "Remove the applied wallpaper, theme colors, and custom icons",
                onClick  = { viewModel.resetTheme() },
            )

            // ── Install ───────────────────────────────────────────────────
            SettingsGroup("Install")

            // Convert an official PSP theme the user owns: wallpaper + derived color,
            // rendered with our icons (docs/ptf-import-plan.md).
            SettingsRow(
                label    = "Import PSP Theme (.ptf)",
                sublabel = "Uses the theme's wallpaper and color — icons stay ours",
                onClick  = if (state.isInstalling) null else ({ ptfPicker.launch(arrayOf("*/*")) }),
            )

            // A theme shared from another PlayFieldPortal (the Share action on a card).
            SettingsRow(
                label    = "Import Theme (.pfptheme)",
                sublabel = "A theme shared from PlayFieldPortal",
                onClick  = if (state.isInstalling) null else ({ pfpPicker.launch(arrayOf("*/*")) }),
            )

            if (state.isInstalling) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 48.dp, vertical = 4.dp),
                )
            }

            state.installMessage?.let { msg ->
                SettingsRow(
                    label    = msg,
                    sublabel = "Tap to dismiss",
                    onClick  = { viewModel.dismissMessage() },
                )
            }

        }
    }

    // Context menu over the whole screen (controller: hover + options button; touch can tap
    // rows). Same PSP right-edge panel as the XMB's Y/Triangle menu.
    menu?.let { m ->
        PspContextMenuOverlay(
            title          = m.title,
            rows           = m.options.map { PspMenuRow(it.label, it.destructive) },
            selectedIndex  = menuIndex,
            onRowActivated = { index -> m.options.getOrNull(index)?.action?.invoke(); menu = null },
            onDismiss      = { menu = null },
        )
    }

    // Custom icon-color HSV picker. Controller drives it via onInterceptAction above; touch can
    // tap the bars. Commit writes the color through the same setIconColor path as the presets.
    if (customPicker) {
        IconColorCustomPicker(
            hue             = pickerHue,
            saturation      = pickerSat,
            brightness      = pickerVal,
            selectedChannel = pickerChannel,
            onSelectChannel = { pickerChannel = it },
            onChannelFraction = { channel, fraction ->
                pickerChannel = channel
                when (channel) {
                    0 -> pickerHue = (fraction * 360f).coerceIn(0f, 360f)
                    1 -> pickerSat = fraction.coerceIn(0f, 1f)
                    else -> pickerVal = fraction.coerceIn(0f, 1f)
                }
            },
            onConfirm = {
                viewModel.setIconColor(hsvToArgbLong(pickerHue, pickerSat, pickerVal))
                customPicker = false
            },
            onCancel = { customPicker = false },
        )
    }

    }  // wrapping Box
}

// ── Controller context menu for a theme (Apply / Share / Remove) ────────────────

private data class ThemeMenuOption(
    val label: String,
    val destructive: Boolean = false,
    val action: () -> Unit,
)

private data class ThemeMenu(
    val title: String,
    val options: List<ThemeMenuOption>,
)


// ── Focusable wrapper for a horizontal card strip ───────────────────────────────
//
// Registers ONE focus target with the SettingsScaffold nav (Up/Down land on the strip like a
// row); the screen handles Left/Right internally to move between cards while focused.
@Composable
private fun FocusableStrip(
    onFocusChange: (Boolean) -> Unit,
    onSelect: () -> Unit,
    content: @Composable (focused: Boolean) -> Unit,
) {
    val focusTracker  = LocalSettingsFocusTracker.current
    val registerFirst = LocalSettingsRegisterFirstFocusable.current
    val rowPositions  = LocalSettingsRowPositions.current
    val reportFocused = LocalSettingsReportFocused.current
    val reportRemoved = LocalSettingsReportRemoved.current
    var isFocused by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }

    DisposableEffect(Unit) {
        registerFirst(fr)
        onDispose {
            rowPositions?.remove(fr)
            reportRemoved(fr)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(fr)
            .onGloballyPositioned { rowPositions?.put(fr, it.localToRoot(Offset.Zero).y) }
            .onFocusChanged { st ->
                isFocused = st.isFocused
                onFocusChange(st.isFocused)
                if (st.isFocused) {
                    focusTracker(onSelect)
                    reportFocused(fr)
                }
            }
            .focusable(),
    ) { content(isFocused) }
}

// Preset icon tints. "Default" (null) = white — the icon art's native color. Kept to a small
// curated set; per Sony's own guidance a flat icon color must stay legible over any wallpaper,
// and the wallpaper scrim covers these (docs/icon-system-plan.md).
private val IconColorChoices: List<Pair<String, Long?>> = listOf(
    "Default"  to null,
    "Pink"     to 0xFFFFD6E8L,
    "Gold"     to 0xFFE8C64AL,
    "Aqua"     to 0xFF7FD8D8L,
    "Sky Blue" to 0xFF9DBEF5L,
    "Green"    to 0xFF9FDB9FL,
    "Coral"    to 0xFFE88A8AL,
    "Slate"    to 0xFFAAB2BFL,
)

@Composable
private fun IconColorSwatchRow(
    selectedArgb: Long?,
    focusedIndex: Int?,
    onSelectPreset: (Long?) -> Unit,
    onSelectCustom: () -> Unit,
) {
    val presetArgbs = IconColorChoices.map { it.second }
    val customActive = selectedArgb != null && selectedArgb !in presetArgbs
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 10.dp),
    ) {
        IconColorChoices.forEachIndexed { index, (label, argb) ->
            IconSwatch(
                label = label,
                fill = Color(argb ?: 0xFFFFFFFFL),
                brush = null,
                selected = selectedArgb == argb,
                focused = focusedIndex == index,
                onClick = { onSelectPreset(argb) },
            )
        }
        // The Custom swatch: shows the active custom color, else a rainbow wheel hint.
        IconSwatch(
            label = "Custom",
            fill = if (customActive) Color(selectedArgb!! and 0xFFFFFFFFL) else null,
            brush = if (customActive) null else rainbowBrush(),
            selected = customActive,
            focused = focusedIndex == IconColorChoices.size,
            onClick = onSelectCustom,
        )
    }
}

@Composable
private fun IconSwatch(
    label: String,
    fill: Color?,
    brush: Brush?,
    selected: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
) {
    val ringColor = if (focused || selected) SettingsAccent else Color(0x66FFFFFF)
    val ringWidth = if (focused) 3.dp else if (selected) 2.dp else 1.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .then(if (fill != null) Modifier.background(fill) else Modifier.background(brush!!))
                .border(ringWidth, ringColor, CircleShape)
                .clickable(onClick = onClick),
        )
        Text(
            text = label,
            color = if (focused || selected) SettingsAccent else SettingsSubtext,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ── Custom icon-color HSV picker ────────────────────────────────────────────────
//
// A controller-first color picker: Up/Down pick a channel (Hue / Saturation / Brightness),
// Left/Right adjust it, A applies, B cancels. Touch can tap a bar to set it directly.
@Composable
private fun IconColorCustomPicker(
    hue: Float,
    saturation: Float,
    brightness: Float,
    selectedChannel: Int,
    onSelectChannel: (Int) -> Unit,
    onChannelFraction: (channel: Int, fraction: Float) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val preview = hsvColor(hue, saturation, brightness)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .clickable(onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .width(440.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF15151F))
                .clickable(onClick = {}) // consume taps so the scrim doesn't dismiss
                .padding(24.dp),
        ) {
            Text("Custom Icon Color", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(preview)
                        .border(1.dp, Color(0x66FFFFFF), CircleShape),
                )
                Spacer(Modifier.width(16.dp))
                Text(hexOf(preview), color = SettingsSubtext, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            }
            Spacer(Modifier.height(20.dp))
            ChannelBar("Hue", hue / 360f, rainbowBrush(), selectedChannel == 0,
                onFraction = { onChannelFraction(0, it) })
            Spacer(Modifier.height(14.dp))
            ChannelBar("Saturation", saturation,
                Brush.horizontalGradient(listOf(hsvColor(hue, 0f, brightness), hsvColor(hue, 1f, brightness))),
                selectedChannel == 1, onFraction = { onChannelFraction(1, it) })
            Spacer(Modifier.height(14.dp))
            ChannelBar("Brightness", brightness,
                Brush.horizontalGradient(listOf(hsvColor(hue, saturation, 0f), hsvColor(hue, saturation, 1f))),
                selectedChannel == 2, onFraction = { onChannelFraction(2, it) })
            Spacer(Modifier.height(20.dp))
            Text(
                "◄ ► adjust    ▲ ▼ channel    Ⓐ apply    Ⓑ cancel",
                color = SettingsSubtext, fontSize = 12.sp,
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Apply", color = SettingsAccent, fontSize = 15.sp,
                    modifier = Modifier.clickable(onClick = onConfirm).padding(vertical = 6.dp, horizontal = 10.dp))
                Text("Cancel", color = SettingsSubtext, fontSize = 15.sp,
                    modifier = Modifier.clickable(onClick = onCancel).padding(vertical = 6.dp, horizontal = 10.dp))
            }
        }
    }
}

@Composable
private fun ChannelBar(
    label: String,
    fraction: Float,
    brush: Brush,
    selected: Boolean,
    onFraction: (Float) -> Unit,
) {
    Text(label, color = if (selected) SettingsAccent else SettingsSubtext, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(brush)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) SettingsAccent else Color(0x55FFFFFF),
                shape = RoundedCornerShape(14.dp),
            )
            .pointerInput(Unit) {
                detectTapGestures { pos -> onFraction((pos.x / size.width).coerceIn(0f, 1f)) }
            },
    ) {
        val knobX = maxWidth * fraction.coerceIn(0f, 1f)
        Box(
            modifier = Modifier
                .offset(x = knobX - 9.dp)
                .align(Alignment.CenterStart)
                .size(18.dp)
                .clip(CircleShape)
                .background(Color.White)
                .border(2.dp, Color(0x99000000), CircleShape),
        )
    }
}

private fun rainbowBrush(): Brush = Brush.horizontalGradient(
    listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
)

private fun hsvColor(hue: Float, saturation: Float, brightness: Float): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f))))

private fun hsvToArgbLong(hue: Float, saturation: Float, brightness: Float): Long =
    android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, brightness)).toLong() and 0xFFFFFFFFL

private fun hexOf(color: Color): String = String.format("#%06X", 0xFFFFFF and color.toArgb())

/** Horizontally-scrolling cards for the saved .pfptheme library: thumbnail, name, actions.
 *  [focusedIndex] highlights the controller-selected card (null while the strip is unfocused). */
@Composable
private fun SavedThemeCardRow(
    themes: List<PfpThemeStore.SavedTheme>,
    focusedIndex: Int? = null,
    onApply: (String) -> Unit,
    onDelete: (String) -> Unit,
    onShare: (String) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 10.dp),
    ) {
        themes.forEachIndexed { index, theme ->
            val cardFocused = focusedIndex == index
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(width = 168.dp, height = 96.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(theme.accentArgb?.let { it and 0xFFFFFFFFL } ?: 0xFF20304AL))
                        .border(
                            width = if (cardFocused) 3.dp else 1.dp,
                            color = if (cardFocused) SettingsAccent else Color(0x55FFFFFF),
                            shape = RoundedCornerShape(10.dp),
                        )
                        .clickable { onApply(theme.id) },
                ) {
                    theme.previewPath?.let { path ->
                        AsyncImage(
                            model = path,
                            contentDescription = theme.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    // Accent chip in the corner, over the thumbnail.
                    theme.accentArgb?.let { accent ->
                        Box(
                            modifier = Modifier
                                .padding(6.dp)
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(Color(accent and 0xFFFFFFFFL))
                                .border(1.dp, Color(0x88FFFFFF), CircleShape)
                                .align(Alignment.TopEnd),
                        )
                    }
                }
                Text(
                    text = theme.name,
                    color = if (cardFocused) SettingsAccent else SettingsSubtext,
                    fontSize = 12.sp,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Padding INSIDE the clickable enlarges the tap target beyond the small text —
                    // these are finger targets on a handheld, not mouse targets.
                    Text(
                        text = "Share",
                        color = SettingsAccent,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { onShare(theme.id) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    Text(
                        text = "Remove",
                        color = SettingsAccent,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .clickable { onDelete(theme.id) }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** Small round preview of an ARGB color, used as a row trailing element. */
@Composable
private fun ColorDot(argb: Long) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(Color(argb and 0xFFFFFFFFL))
            .border(1.dp, Color(0x66FFFFFF), CircleShape),
    )
}

