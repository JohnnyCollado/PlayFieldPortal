package com.playfieldportal.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.ControllerIcon
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.keyboard.KeyboardPlacement
import com.playfieldportal.core.ui.keyboard.VirtualKeyboardBottomReserve
import com.playfieldportal.core.ui.keyboard.VirtualKeyboardTextInput
import com.playfieldportal.core.ui.keyboard.rememberVirtualKeyboardEdit
import com.playfieldportal.core.ui.keyboard.virtualKeyboardField
import com.playfieldportal.core.ui.sound.LocalMenuSounds
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.theme.contrastRatio
import com.playfieldportal.core.ui.theme.themedSubText
import com.playfieldportal.core.ui.theme.themedText

/**
 * The app's one HSV colour picker: a Hex field over Hue, Saturation and Brightness bars, the four
 * stops [HsvPickerNav] moves between on the navigation core.
 *
 * Stateless. The host keeps an [HsvPickerState], passes every controller press through
 * [HsvPickerNav.handle], and takes [onStateChange] for everything touch and typing do: a tap or a
 * drag on a bar, a tap on the Hex field, each keystroke. ✕ on the Hex field (or a tap) raises the
 * keyboard; six hex digits repaint the colour at once. While PFP's keyboard is up the shell routes
 * every press to it, so the host sees none until it closes.
 *
 * The title and swatch stay pinned and the Hex field comes first under them, so neither keyboard
 * covers what is being typed; everything under them scrolls inside whatever height the card
 * is given, which with PFP's keyboard up is only what sits above [VirtualKeyboardBottomReserve].
 * The focused stop is always brought into view, so the Hex field being typed is never under it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HsvColorPickerDialog(
    title: String,
    state: HsvPickerState,
    onStateChange: (HsvPickerState) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    accent: Color = Color.White,
    subtext: Color = themedSubText(Color.White.copy(alpha = 0.7f)),
    /**
     * Darkest and brightest backdrop the picked colour will actually sit on. When supplied, the
     * dialog shows live contrast readings against both — which is what turns a later automatic
     * adjustment from a surprise into something the user already saw coming.
     */
    contrastAnchors: Pair<Color, Color>? = null,
    /** Ratio below which a reading is called out. 3:1 by decision — see the legibility plan. */
    contrastWarnBelow: Float = 3f,
    showHints: Boolean = true,
) {
    val preview = Color(state.argb)
    val menuSounds = LocalMenuSounds.current
    val current by rememberUpdatedState(state)
    val change by rememberUpdatedState(onStateChange)
    // Two ways out — the scrim and the Cancel label — so they share one lambda and cannot end up
    // sounding different. Dismissing is a level up, whichever one the finger lands on.
    val cancel: () -> Unit = { menuSounds.play(MenuSound.BACK); onCancel() }
    val cursor = state.cursorVisible

    val edit = rememberVirtualKeyboardEdit(
        text = state.hexDigits,
        onTextChange = { change(HsvPickerNav.typeHex(current, it)) },
        placement = KeyboardPlacement.BOTTOM_CENTER,
        maxLength = HEX_DIGITS,
        onDone = { change(HsvPickerNav.endHexEntry(current)) },
        onClose = { change(HsvPickerNav.endHexEntry(current)) },
    )
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // The state is the truth: editing raises a keyboard, leaving it drops it. PFP's is opened first
    // and given a frame, so the field's own keyboard request is already held when focus arrives.
    LaunchedEffect(state.editingHex) {
        if (state.editingHex) {
            if (edit.start()) withFrameNanos { }
            focusRequester.requestFocus()
        } else {
            edit.stop()
            focusManager.clearFocus()
        }
    }

    // One requester per stop. Framed by geometry, as the settings rows are: the scroll parent brings
    // the stop itself into view, whatever height the contrast strip or the keyboard leaves.
    val stopInView = remember { HsvPickerField.entries.associateWith { BringIntoViewRequester() } }
    LaunchedEffect(state.focus, state.editingHex, edit.isOpen, cursor) {
        if (cursor || state.editingHex) {
            // Wait out the relayout the keyboard reserve just caused, so the viewport is its new size.
            withFrameNanos { }
            stopInView.getValue(state.focus).bringIntoView()
        }
    }
    val bodyScroll = rememberScrollState()
    val currentCancel by rememberUpdatedState(cancel)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .testTag(HsvPickerTags.SCRIM)
            // Pointer input rather than clickable: a clickable merges its descendants' semantics.
            .pointerInput(Unit) { detectTapGestures { currentCancel() } },
    ) {
        // PFP draws edge-to-edge, so the system keyboard overlays the window: the picker always
        // lays out in the room above it (as PfpModalScaffold does). Where that room is too small
        // for the card (a phone in landscape) the picker becomes one strip resting on the
        // keyboard: swatch, Hex field, Apply and Cancel — the sliders return once it closes.
        val systemKeyboard = systemKeyboardHeight()
        val pfpReserve = if (edit.isOpen) VirtualKeyboardBottomReserve else 0.dp
        val compact = useCompactImeLayout(systemKeyboard > 0.dp, maxHeight - systemKeyboard - pfpReserve)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(bottom = pfpReserve + systemKeyboard),
        contentAlignment = if (compact) Alignment.BottomCenter else Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .then(
                    if (compact) Modifier.fillMaxWidth(COMPACT_WIDTH_FRACTION).widthIn(max = CompactMaxWidth)
                    else Modifier.width(440.dp),
                )
                .padding(vertical = if (compact) 8.dp else 0.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF15151F))
                // Swallows taps so a click inside the panel does not reach the scrim's cancel.
                .pointerInput(Unit) { detectTapGestures { } }
                // 212dp is all an Odin 3 has above the keyboard; the tighter edge buys the Hex
                // field its room under the pinned swatch.
                .padding(
                    horizontal = if (compact) 20.dp else 24.dp,
                    vertical = if (compact) 12.dp else if (edit.isOpen) 16.dp else 24.dp,
                ),
        ) {
            Text(
                title,
                color = themedText(Color.White),
                fontSize = if (compact) 15.sp else 18.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (!compact) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Swatch(preview, 56.dp)
                    Spacer(Modifier.width(16.dp))
                    Text(hexOf(preview), color = subtext, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                }
            }

            // Shrinks to what the screen leaves rather than running off it, and scrolls within it.
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(bodyScroll),
            ) {
                Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
                val hexActive = state.editingHex || (cursor && state.focus == HsvPickerField.HEX)
                Column(Modifier.bringIntoViewRequester(stopInView.getValue(HsvPickerField.HEX))) {
                    if (!compact) {
                        Text("Hex", color = if (hexActive) accent else subtext, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                    }
                    // The field keeps one place in the tree in both layouts: re-creating it would
                    // drop its focus, close the keyboard and flip the layout straight back.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                    if (compact) {
                        Swatch(preview, 32.dp)
                        Spacer(Modifier.width(12.dp))
                    }
                    Box(Modifier.weight(1f)) {
                    VirtualKeyboardTextInput(edit) {
                        BasicTextField(
                            value = edit.fieldValue,
                            onValueChange = { edit.onFieldValueChange(it) },
                            singleLine = true,
                            textStyle = TextStyle(color = themedText(Color.White), fontSize = 16.sp, fontFamily = FontFamily.Monospace),
                            cursorBrush = SolidColor(accent),
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.Characters,
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Done,
                            ),
                            keyboardActions = KeyboardActions(onDone = { change(HsvPickerNav.endHexEntry(current)) }),
                            modifier = Modifier
                                .testTag(HsvPickerTags.HEX_FIELD)
                                .fillMaxWidth()
                                .virtualKeyboardField(edit)
                                .focusRequester(focusRequester)
                                // A tap lands here before the host knows about it; tell it, so the cursor
                                // follows and the field counts as being edited.
                                .onFocusChanged { focus ->
                                    if (focus.isFocused && !current.editingHex) {
                                        change(HsvPickerNav.touch(current, HsvPickerField.HEX).copy(editingHex = true))
                                    }
                                },
                            decorationBox = { inner ->
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White.copy(alpha = 0.06f))
                                        .border(
                                            width = if (hexActive) 2.dp else 1.dp,
                                            color = if (hexActive) accent else Color(0x55FFFFFF),
                                            shape = RoundedCornerShape(10.dp),
                                        )
                                        .padding(horizontal = 12.dp),
                                ) {
                                    Text("#", color = subtext, fontSize = 16.sp, fontFamily = FontFamily.Monospace)
                                    Box(Modifier.weight(1f)) { inner() }
                                }
                            },
                        )
                    }
                    }
                    if (compact) {
                        Spacer(Modifier.width(12.dp))
                        PickerActions(accent, subtext, onApply = { menuSounds.play(MenuSound.CONFIRM); onConfirm() }, onCancel = cancel)
                    }
                    }
                }

                if (!compact) {

                if (contrastAnchors != null) {
                    Spacer(Modifier.height(16.dp))
                    ContrastStrip(preview, contrastAnchors.first, contrastAnchors.second, subtext, contrastWarnBelow)
                }

                val bars = listOf(
                    Triple(HsvPickerField.HUE, "Hue", state.hue / 360f),
                    Triple(HsvPickerField.SATURATION, "Saturation", state.saturation),
                    Triple(HsvPickerField.BRIGHTNESS, "Brightness", state.brightness),
                )
                bars.forEach { (field, label, fraction) ->
                    Spacer(Modifier.height(if (field == HsvPickerField.HUE) 20.dp else 14.dp))
                    Column(
                        Modifier
                            .bringIntoViewRequester(stopInView.getValue(field))
                            .then(if (field == HsvPickerField.HUE) Modifier.testTag(HsvPickerTags.HUE_BAR) else Modifier),
                    ) {
                        ChannelBar(
                            label = label,
                            fraction = fraction,
                            brush = when (field) {
                                HsvPickerField.HUE -> rainbowBrush()
                                HsvPickerField.SATURATION -> Brush.horizontalGradient(
                                    listOf(hsvColor(state.hue, 0f, state.brightness), hsvColor(state.hue, 1f, state.brightness)),
                                )
                                else -> Brush.horizontalGradient(
                                    listOf(hsvColor(state.hue, state.saturation, 0f), hsvColor(state.hue, state.saturation, 1f)),
                                )
                            },
                            selected = cursor && state.focus == field,
                            accent = accent,
                            subtext = subtext,
                            onTap = { menuSounds.play(MenuSound.SCROLL); change(HsvPickerNav.touchBar(current, field, it)) },
                            onDrag = { change(HsvPickerNav.touchBar(current, field, it)) },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                if (showHints && !state.editingHex) {
                    ControllerHintBar(items = hintItems(state.focus), compact = true)
                    Spacer(Modifier.height(12.dp))
                }
                PickerActions(accent, subtext, onApply = { menuSounds.play(MenuSound.CONFIRM); onConfirm() }, onCancel = cancel)
                }
            }
        }
    }
    }
}

private const val COMPACT_WIDTH_FRACTION = 0.94f
private val CompactMaxWidth = 820.dp

@Composable
private fun Swatch(color: Color, size: Dp) {
    Box(
        modifier = Modifier
            .testTag(HsvPickerTags.SWATCH)
            .size(size)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, Color(0x66FFFFFF), CircleShape),
    )
}

/** Apply and Cancel — under the sliders, or beside the Hex field in the compact strip. */
@Composable
private fun PickerActions(accent: Color, subtext: Color, onApply: () -> Unit, onCancel: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            "Apply",
            color = accent,
            fontSize = 15.sp,
            modifier = Modifier
                .testTag(HsvPickerTags.APPLY)
                .clickable(onClick = onApply)
                .padding(vertical = 6.dp, horizontal = 10.dp),
        )
        Text(
            "Cancel",
            color = subtext,
            fontSize = 15.sp,
            modifier = Modifier
                .testTag(HsvPickerTags.CANCEL)
                .clickable(onClick = onCancel)
                .padding(vertical = 6.dp, horizontal = 10.dp),
        )
    }
}

/** Test tags for the picker's scrim, swatch and Hex field. */
object HsvPickerTags {
    const val SCRIM = "hsv_picker_scrim"
    const val SWATCH = "hsv_picker_swatch"
    const val HEX_FIELD = "hsv_picker_hex_field"
    const val HUE_BAR = "hsv_picker_hue_bar"
    const val APPLY = "hsv_picker_apply"
    const val CANCEL = "hsv_picker_cancel"
}

private const val HEX_DIGITS = 6

private val MoveIcons = listOf(ControllerIcon.DPAD_UP, ControllerIcon.DPAD_DOWN)
private val AdjustIcons = listOf(ControllerIcon.DPAD_LEFT, ControllerIcon.DPAD_RIGHT)

/** Every button the focused stop answers to: ✕ applies on a bar and types on Hex. */
private fun hintItems(focus: HsvPickerField): List<ControllerPromptItem> =
    if (focus == HsvPickerField.HEX) {
        listOf(
            ControllerPromptItem.fixed(MoveIcons, "Move"),
            ControllerPromptItem(GamepadAction.SELECT, "Type hex"),
            ControllerPromptItem(GamepadAction.BACK, "Cancel"),
        )
    } else {
        listOf(
            ControllerPromptItem.fixed(AdjustIcons, "Adjust"),
            ControllerPromptItem.fixed(MoveIcons, "Move"),
            ControllerPromptItem(GamepadAction.SELECT, "Apply"),
            ControllerPromptItem(GamepadAction.BACK, "Cancel"),
        )
    }

/**
 * Sample text in the picked colour over the darkest and brightest places it will land, with the
 * measured ratio under each. Neither of the old pickers had this; it costs ~20 lines because
 * `contrastRatio` already exists, and it makes the adjustment self-explanatory.
 */
@Composable
private fun ContrastStrip(
    color: Color,
    darkest: Color,
    brightest: Color,
    subtext: Color,
    warnBelow: Float,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        listOf(darkest, brightest).forEach { backdrop ->
            val ratio = contrastRatio(color, backdrop).toFloat()
            Column(modifier = Modifier.weight(1f)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(backdrop)
                        .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Sample text", color = color, fontSize = 14.sp)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = String.format("%.2f:1", ratio) + if (ratio < warnBelow) "  ⚠" else "",
                    color = if (ratio < warnBelow) Color(0xFFFFC857) else subtext,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                )
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
    accent: Color,
    subtext: Color,
    onTap: (Float) -> Unit,
    onDrag: (Float) -> Unit,
) {
    // A tap lands the knob somewhere new and ticks like a D-pad step; a drag follows the finger
    // silently, the way the settings sliders do.
    val tap by rememberUpdatedState(onTap)
    val drag by rememberUpdatedState(onDrag)
    Text(label, color = if (selected) accent else subtext, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(brush)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) accent else Color(0x55FFFFFF),
                shape = RoundedCornerShape(14.dp),
            )
            .pointerInput(Unit) {
                detectTapGestures { pos -> tap((pos.x / size.width).coerceIn(0f, 1f)) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    drag((change.position.x / size.width).coerceIn(0f, 1f))
                }
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

// ── Preset swatches ───────────────────────────────────────────────────────────

/** The shared preset list. `null` means "no override — inherit the theme's own colour". */
val PfpColorChoices: List<Pair<String, Long?>> = listOf(
    "Default" to null,
    "Pink" to 0xFFFFD6E8L,
    "Gold" to 0xFFE8C64AL,
    "Aqua" to 0xFF7FD8D8L,
    "Sky Blue" to 0xFF9DBEF5L,
    "Green" to 0xFF9FDB9FL,
    "Coral" to 0xFFE88A8AL,
    "Slate" to 0xFFAAB2BFL,
)

/**
 * Horizontal row of preset swatches plus a rainbow "Custom" entry, which is selected whenever the
 * stored colour is not one of the presets.
 */
@Composable
fun ColorSwatchRow(
    selectedArgb: Long?,
    focusedIndex: Int?,
    accent: Color,
    subtext: Color,
    onSelectPreset: (Long?) -> Unit,
    onSelectCustom: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presetArgbs = PfpColorChoices.map { it.second }
    val customActive = selectedArgb != null && selectedArgb !in presetArgbs
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 48.dp, vertical = 10.dp),
    ) {
        PfpColorChoices.forEachIndexed { index, (label, argb) ->
            ColorSwatch(
                label = label,
                fill = Color(argb ?: 0xFFFFFFFFL),
                brush = null,
                selected = selectedArgb == argb,
                focused = focusedIndex == index,
                accent = accent,
                subtext = subtext,
                onClick = { onSelectPreset(argb) },
            )
        }
        ColorSwatch(
            label = "Custom",
            fill = selectedArgb?.takeIf { customActive }?.let { Color(it and 0xFFFFFFFFL) },
            brush = if (customActive) null else rainbowBrush(),
            selected = customActive,
            focused = focusedIndex == PfpColorChoices.size,
            accent = accent,
            subtext = subtext,
            onClick = onSelectCustom,
        )
    }
}

@Composable
private fun ColorSwatch(
    label: String,
    fill: Color?,
    brush: Brush?,
    selected: Boolean,
    focused: Boolean,
    accent: Color,
    subtext: Color,
    onClick: () -> Unit,
) {
    val menuSounds = LocalMenuSounds.current
    // The selection ring stays accent-coloured: a ring is a fill, not a text run, and the
    // affordance was always carried by the ring rather than by the label's colour.
    val ringColor = if (focused || selected) accent else Color(0x66FFFFFF)
    val ringWidth = if (focused) 3.dp else if (selected) 2.dp else 1.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .then(
                    when {
                        fill != null -> Modifier.background(fill)
                        brush != null -> Modifier.background(brush)
                        else -> Modifier.background(Color.Transparent)
                    }
                )
                .border(BorderStroke(ringWidth, ringColor), CircleShape)
                .clickable { menuSounds.play(MenuSound.SELECT); onClick() },
        )
        Text(
            text = label,
            color = if (focused || selected) accent else subtext,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

// ── Shared colour helpers ─────────────────────────────────────────────────────

private fun rainbowBrush(): Brush = Brush.horizontalGradient(
    listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
)

private fun hsvColor(hue: Float, saturation: Float, brightness: Float): Color = Color(hsvToArgb(hue, saturation, brightness))

fun hexOf(color: Color): String = String.format("#%06X", 0xFFFFFF and color.toArgb())
