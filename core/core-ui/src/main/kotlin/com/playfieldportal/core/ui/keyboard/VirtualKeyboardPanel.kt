package com.playfieldportal.core.ui.keyboard

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.R
import com.playfieldportal.core.ui.components.ControllerHintBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.preview.PfpPreview
import com.playfieldportal.core.ui.theme.menuCursor

// ── Approved look (Virtual Keyboard plan section 4) ─────────────────────────────

private val PanelPadding = VirtualKeyboardMetrics.PanelPadding
private val PanelCorner = 10.dp
// Near-opaque: at the mockup's 86% the screen behind (an app grid's icons and names) read through
// the keys on device (2026-10-01).
private val PanelFill = Color.Black.copy(alpha = 0.96f)
private val KeyGap = VirtualKeyboardMetrics.KeyGap
private val KeyCorner = 6.dp
// Key fills raised from the mockup's 10% / 5% / 34% after the device pass (2026-10-01): the keys
// read too faint to scan. Function keys stay a step quieter than letters; a lit Shift a step louder.
private val KeyFill = Color.White.copy(alpha = 0.22f)
private val FunctionKeyFill = Color.White.copy(alpha = 0.14f)
// Shift while on — one-shot or locked (approved 2026-10-01). A lock adds a bar under the arrow.
private val ShiftOnFill = Color.White.copy(alpha = 0.45f)
private const val DISABLED_KEY_ALPHA = 0.35f

/**
 * Key sizing. One size serves touch and controller alike, so the panel never jumps when input
 * switches mid-typing. Two ways in: [forWidth] (a panel hung under its field, sized by the width on
 * offer) and [forKeyHeight] (the bottom keyboard, whose height matches the system keyboard's; see
 * [virtualKeyboardBottomReserve]). Every host that reserves room for the panel derives from these.
 */
internal data class VirtualKeyboardMetrics(val keyUnit: Dp, val keyHeight: Dp) {
    companion object {
        val PanelPadding = 8.dp
        val KeyGap = 3.dp
        const val COLUMNS = 10
        const val ROWS = 4
        private val MaxKeyUnit = 48.dp   // Android's touch-target guideline
        private val MinKeyHeight = 44.dp
        /** The smallest key a matched keyboard shrinks to: about Gboard's on a short screen. */
        val MinMatchedKeyHeight = 36.dp
        private const val HEIGHT_PER_UNIT = 32f / 34f // the original compact key's proportions

        private fun heightFor(unit: Dp): Dp = maxOf(MinKeyHeight, unit * HEIGHT_PER_UNIT)

        private fun widthFit(availableWidth: Dp): Dp =
            maxOf((availableWidth - PanelPadding * 2 - KeyGap * (COLUMNS - 1)) / COLUMNS, 0.dp)

        /** The key unit that fits ten columns, gaps and padding in [availableWidth], capped at 48 dp. */
        fun forWidth(availableWidth: Dp): VirtualKeyboardMetrics {
            val unit = minOf(MaxKeyUnit, widthFit(availableWidth))
            return VirtualKeyboardMetrics(unit, heightFor(unit))
        }

        /**
         * Keys [keyHeight] tall, as wide as the key's proportions ask for and the width allows: a tall
         * key on a tablet is a wide one too, as on the system keyboard.
         */
        fun forKeyHeight(keyHeight: Dp, availableWidth: Dp): VirtualKeyboardMetrics =
            VirtualKeyboardMetrics(minOf(widthFit(availableWidth), keyHeight / HEIGHT_PER_UNIT), keyHeight)

        /** The panel's height for keys [keyHeight] tall. */
        fun panelHeight(keyHeight: Dp): Dp = keyHeight * ROWS + KeyGap * (ROWS - 1) + PanelPadding * 2

        /** The key height that makes the panel [panelHeight] tall, never below [MinMatchedKeyHeight]. */
        fun keyHeightFor(panelHeight: Dp): Dp =
            maxOf(MinMatchedKeyHeight, (panelHeight - PanelPadding * 2 - KeyGap * (ROWS - 1)) / ROWS)
    }
}

/** Width of a key spanning [span] columns, gaps between its columns included. */
private fun keyWidth(unit: Dp, span: Int): Dp = unit * span + KeyGap * (span - 1)

const val VIRTUAL_KEY_TAG_PREFIX = "vk_key"

fun virtualKeyTag(row: Int, cell: Int) = "${VIRTUAL_KEY_TAG_PREFIX}_${row}_$cell"

/** The keyboard's prompt pill, in the approved order — plus START as Done (plan §10 Q3, 2026-10-01). */
@Composable
fun virtualKeyboardPrompts(): List<ControllerPromptItem> = listOf(
    ControllerPromptItem(GamepadAction.SELECT, stringResource(R.string.core_vk_prompt_type)),
    ControllerPromptItem(GamepadAction.CHANGE_SORT, stringResource(R.string.core_vk_prompt_delete)),
    ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, stringResource(R.string.core_vk_key_space)),
    ControllerPromptItem(
        listOf(GamepadAction.PREV_CATEGORY, GamepadAction.NEXT_CATEGORY),
        stringResource(R.string.core_vk_prompt_cursor),
    ),
    ControllerPromptItem(GamepadAction.SHIFT, stringResource(R.string.core_vk_key_shift)),
    ControllerPromptItem(GamepadAction.CAPS_LOCK, stringResource(R.string.core_vk_prompt_caps)),
    ControllerPromptItem(GamepadAction.HOME, stringResource(R.string.core_vk_key_done)),
    ControllerPromptItem(GamepadAction.BACK, stringResource(R.string.core_close)),
)

/**
 * The keyboard panel: four rows of ten columns, the focused key under the menu cursor. Keys scale
 * with the width on offer up to 48 dp ([VirtualKeyboardMetrics.forWidth]), or are [keyHeight] tall
 * when the host matches the system keyboard's height ([VirtualKeyboardMetrics.forKeyHeight]).
 */
@Composable
fun VirtualKeyboardPanel(
    state: VirtualKeyboardState,
    modifier: Modifier = Modifier,
    keyHeight: Dp? = null,
) {
    BoxWithConstraints(modifier) {
        val width = if (constraints.hasBoundedWidth) maxWidth else 960.dp
        val metrics = keyHeight?.let { VirtualKeyboardMetrics.forKeyHeight(it, width) } ?: VirtualKeyboardMetrics.forWidth(width)
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(PanelCorner))
                .background(PanelFill)
                .padding(PanelPadding),
            verticalArrangement = Arrangement.spacedBy(KeyGap),
        ) {
            VirtualKeyboardLayout.rows(state.layer).forEachIndexed { row, keys ->
                Row(horizontalArrangement = Arrangement.spacedBy(KeyGap)) {
                    keys.forEachIndexed { cell, key ->
                        KeyCap(
                            key = key,
                            shift = state.shift,
                            focused = state.focus.row == row && state.focus.cell == cell,
                            metrics = metrics,
                            modifier = Modifier.testTag(virtualKeyTag(row, cell)),
                        )
                    }
                }
            }
        }
    }
}

/** The prompt pill under the panel. */
@Composable
fun VirtualKeyboardHintBar(modifier: Modifier = Modifier, background: Color = Color.Black.copy(alpha = 0.92f)) {
    ControllerHintBar(items = virtualKeyboardPrompts(), modifier = modifier, background = background)
}

@Composable
private fun KeyCap(key: VirtualKey, shift: ShiftMode, focused: Boolean, metrics: VirtualKeyboardMetrics, modifier: Modifier) {
    val enabled = key !is VirtualKey.Shift || key.enabled
    val isFunctionKey = key !is VirtualKey.Character && key != VirtualKey.Space
    val shiftLit = key is VirtualKey.Shift && enabled && shift != ShiftMode.OFF
    val fill = when {
        shiftLit -> ShiftOnFill
        isFunctionKey -> FunctionKeyFill
        else -> KeyFill
    }
    val shape = RoundedCornerShape(KeyCorner)
    val description = when (key) {
        is VirtualKey.Shift -> stringResource(R.string.core_vk_key_shift)
        VirtualKey.Space -> stringResource(R.string.core_vk_key_space)
        VirtualKey.Backspace -> stringResource(R.string.core_vk_key_backspace)
        VirtualKey.Done -> stringResource(R.string.core_vk_key_done)
        else -> null
    }
    val shiftStateDescription = (key as? VirtualKey.Shift)?.let {
        stringResource(
            when (shift) {
                ShiftMode.OFF -> R.string.core_vk_shift_off
                ShiftMode.ONCE -> R.string.core_vk_shift_once
                ShiftMode.HELD -> R.string.core_vk_shift_held
                ShiftMode.LOCKED -> R.string.core_vk_shift_locked
            },
        )
    }
    Box(
        modifier = modifier
            .width(keyWidth(metrics.keyUnit, key.span))
            .height(metrics.keyHeight)
            .clip(shape)
            .background(fill)
            .menuCursor(selected = focused, shape = shape)
            .alpha(if (enabled) 1f else DISABLED_KEY_ALPHA)
            .semantics {
                selected = focused
                if (description != null) contentDescription = description
                if (shiftStateDescription != null) stateDescription = shiftStateDescription
                if (!enabled) disabled()
            },
        contentAlignment = Alignment.Center,
    ) {
        when (key) {
            is VirtualKey.Character ->
                KeyLabel(if (shift != ShiftMode.OFF) key.char.uppercaseChar().toString() else key.char.toString(), small = false)
            is VirtualKey.LayerSwitch -> KeyLabel(key.label, small = true)
            VirtualKey.Done -> KeyLabel(stringResource(R.string.core_vk_key_done), small = true)
            is VirtualKey.Shift -> KeyGlyph { shiftArrow(locked = shift == ShiftMode.LOCKED && key.enabled) }
            VirtualKey.Space -> KeyGlyph { spaceBracket() }
            VirtualKey.Backspace -> KeyGlyph { backspaceTag() }
        }
    }
}

@Composable
private fun KeyLabel(text: String, small: Boolean) {
    Text(
        text = text,
        color = Color.White,
        fontSize = if (small) 12.sp else 15.sp,
        fontWeight = if (small) FontWeight.SemiBold else FontWeight.Normal,
    )
}

@Composable
private fun KeyGlyph(draw: DrawScope.() -> Unit) {
    Canvas(modifier = Modifier.size(18.dp), onDraw = draw)
}

private fun DrawScope.glyphStroke() = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)

// ⇧: an outlined up-arrow; caps lock lifts it and draws a bar beneath.
private fun DrawScope.shiftArrow(locked: Boolean) {
    val w = size.width
    val h = size.height
    val bottom = if (locked) 0.7f else 0.88f
    val path = Path().apply {
        moveTo(w * 0.5f, h * 0.08f)
        lineTo(w * 0.9f, h * 0.48f)
        lineTo(w * 0.68f, h * 0.48f)
        lineTo(w * 0.68f, h * bottom)
        lineTo(w * 0.32f, h * bottom)
        lineTo(w * 0.32f, h * 0.48f)
        lineTo(w * 0.1f, h * 0.48f)
        close()
    }
    val stroke = glyphStroke()
    drawPath(path, Color.White, style = stroke)
    if (locked) {
        drawLine(Color.White, Offset(w * 0.32f, h * 0.9f), Offset(w * 0.68f, h * 0.9f), stroke.width, StrokeCap.Round)
    }
}

// ⎵: an open bracket lying on its back.
private fun DrawScope.spaceBracket() {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(0f, h * 0.45f)
        lineTo(0f, h * 0.72f)
        lineTo(w, h * 0.72f)
        lineTo(w, h * 0.45f)
    }
    drawPath(path, Color.White, style = glyphStroke())
}

// ⌫: a left-pointing tag with an × inside.
private fun DrawScope.backspaceTag() {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.05f, h * 0.5f)
        lineTo(w * 0.32f, h * 0.2f)
        lineTo(w * 0.95f, h * 0.2f)
        lineTo(w * 0.95f, h * 0.8f)
        lineTo(w * 0.32f, h * 0.8f)
        close()
    }
    val stroke = glyphStroke()
    drawPath(path, Color.White, style = stroke)
    drawLine(Color.White, Offset(w * 0.5f, h * 0.36f), Offset(w * 0.76f, h * 0.64f), stroke.width, StrokeCap.Round)
    drawLine(Color.White, Offset(w * 0.76f, h * 0.36f), Offset(w * 0.5f, h * 0.64f), stroke.width, StrokeCap.Round)
}

@Preview(widthDp = 420, heightDp = 260)
@Composable
private fun VirtualKeyboardPanelPreview() {
    PfpPreview {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(8.dp)) {
            VirtualKeyboardPanel(VirtualKeyboardReducer.open("Persona"))
            VirtualKeyboardHintBar()
        }
    }
}

@Preview(widthDp = 420, heightDp = 220)
@Composable
private fun VirtualKeyboardSymbolsPreview() {
    PfpPreview {
        VirtualKeyboardPanel(
            VirtualKeyboardReducer.open("").copy(layer = KeyboardLayer.SYMBOLS),
            modifier = Modifier.padding(8.dp),
        )
    }
}
