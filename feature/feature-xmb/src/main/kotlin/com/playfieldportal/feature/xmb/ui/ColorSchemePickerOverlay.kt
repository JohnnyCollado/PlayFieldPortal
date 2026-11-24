package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.core.ui.text.asString
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import com.playfieldportal.core.domain.model.ColorNaming
import com.playfieldportal.core.domain.model.ControllerIcon
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.XmbSchemeVariant
import com.playfieldportal.core.domain.model.lightBackgroundAnchors
import com.playfieldportal.core.ui.components.ControllerPromptBar
import com.playfieldportal.core.ui.components.ControllerPromptItem
import com.playfieldportal.core.ui.components.HsvColorPickerDialog
import com.playfieldportal.core.ui.components.HsvPickerState
import com.playfieldportal.core.ui.components.hexOf
import com.playfieldportal.core.ui.theme.ContrastVerdict
import com.playfieldportal.core.ui.theme.contrastReadout
import com.playfieldportal.core.ui.theme.themedSubText
import com.playfieldportal.core.ui.theme.themedText
import com.playfieldportal.core.ui.theme.worstContrast
import com.playfieldportal.feature.xmb.viewmodel.ColorSchemeOption
import com.playfieldportal.feature.xmb.viewmodel.ColorSchemePickerState
import com.playfieldportal.feature.xmb.viewmodel.previewArgb
import com.playfieldportal.feature.xmb.viewmodel.selected
import com.playfieldportal.feature.xmb.viewmodel.variantsAvailable
import androidx.compose.ui.res.stringResource
import com.playfieldportal.feature.xmb.R

private val PickerWidth = 320.dp
private val PanelEdgeGap = 20.dp

private val PickerTextShadow = Shadow(
    color = Color.Black.copy(alpha = 0.75f),
    offset = Offset(0f, 2f),
    blurRadius = 4f,
)

/**
 * The colour-scheme list, previewed live on the XMB behind it. Under the list sit the highlighted
 * preset's Tint and Depth steppers and a readout — the shade's name, its hex code and how well the
 * menu text reads on it — so a shade can be chosen from words and the full-screen preview rather
 * than from a small swatch. The D-pad steps the tint and the bumpers the depth; touch taps the
 * arrows.
 *
 * [menuText] is the colour the XMB's labels draw in; [showContrast] is false under a wallpaper,
 * where the scheme no longer paints the backdrop the text sits on.
 */
@Composable
fun ColorSchemePickerOverlay(
    state: ColorSchemePickerState,
    onHighlightedAt: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    onStep: (tint: Int, depth: Int) -> Unit = { _, _ -> },
    menuText: Color = Color.White,
    showContrast: Boolean = true,
    showHints: Boolean = true,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.selectedIndex) {
        if (state.options.isNotEmpty()) {
            listState.animateScrollToItem(state.selectedIndex.coerceIn(0, state.options.lastIndex))
        }
    }

    // The stepped shade, so the panel itself is a large sample of what is being chosen.
    val highlightSwatch = state.previewArgb
    val backdrop by animateColorAsState(
        targetValue = Color(highlightSwatch).copy(alpha = 0.70f),
        label = "colorSchemeBackdrop",
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0x33000000))
            .clickable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(PickerWidth)
                .background(backdrop)
                .clickable {}
                // Top and bottom too: the list now takes all the height the controls leave, so
                // without them the title and the readout sit on the screen edge and the theme's frame.
                .padding(start = 28.dp, top = PanelEdgeGap, end = 36.dp, bottom = PanelEdgeGap),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.xmbu_color_scheme_title),
                fontSize = 19.sp,
                fontWeight = FontWeight.Light,
                color = themedText(Color.White.copy(alpha = 0.92f)),
                style = TextStyle(shadow = PickerTextShadow),
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(end = 8.dp)
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.30f)),
            )
            LazyColumn(
                state = listState,
                // Gives way to the steppers and readout under it, which never scroll off.
                modifier = Modifier.padding(top = 8.dp).weight(1f, fill = false),
            ) {
                itemsIndexed(state.options) { index, option ->
                    ColorSchemeRow(
                        option = option,
                        isSelected = index == state.selectedIndex,
                        onClick = { if (index == state.selectedIndex) onConfirm() else onHighlightedAt(index) },
                    )
                }
            }
            ShadeControls(state, onStep, menuText, showContrast, showHints)
        }
    }
}

/** Test tags for the shade steppers' arrows. */
object ColorSchemePickerTags {
    const val TINT_PREVIOUS = "color_scheme_tint_previous"
    const val TINT_NEXT = "color_scheme_tint_next"
    const val DEPTH_PREVIOUS = "color_scheme_depth_previous"
    const val DEPTH_NEXT = "color_scheme_depth_next"
}

@Composable
private fun ShadeControls(
    state: ColorSchemePickerState,
    onStep: (tint: Int, depth: Int) -> Unit,
    menuText: Color,
    showContrast: Boolean,
    showHints: Boolean,
) {
    val preview = state.previewArgb
    val tintTitle = stringResource(R.string.xmbu_color_scheme_tint)
    val depthTitle = stringResource(R.string.xmbu_color_scheme_depth)
    val hintTint = ControllerPromptItem.fixed(listOf(ControllerIcon.DPAD_LEFT, ControllerIcon.DPAD_RIGHT), tintTitle)
    val hintDepth = ControllerPromptItem(listOf(GamepadAction.PREV_CATEGORY, GamepadAction.NEXT_CATEGORY), depthTitle)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Box(Modifier.fillMaxWidth().padding(end = 8.dp).height(1.dp).background(Color.White.copy(alpha = 0.30f)))
        if (state.variantsAvailable) {
            val base = state.selected?.swatch ?: preview
            val variant = state.variant
            Spacer(Modifier.height(6.dp))
            ShadeStepper(
                title = tintTitle,
                hint = hintTint.takeIf { showHints },
                value = ColorNaming.tintLabel(base, variant.tint).asString(),
                step = variant.tint,
                previousTag = ColorSchemePickerTags.TINT_PREVIOUS,
                nextTag = ColorSchemePickerTags.TINT_NEXT,
                onStep = { onStep(it, 0) },
            )
            ShadeStepper(
                title = depthTitle,
                hint = hintDepth.takeIf { showHints },
                value = ColorNaming.depthLabel(variant.depth).asString(),
                step = variant.depth,
                previousTag = ColorSchemePickerTags.DEPTH_PREVIOUS,
                nextTag = ColorSchemePickerTags.DEPTH_NEXT,
                onStep = { onStep(0, it) },
            )
        }
        Spacer(Modifier.height(6.dp))
        // One line: the name, then its hex code on the same baseline. A long name wraps and the
        // code stays at its end.
        Row(Modifier.fillMaxWidth().padding(end = 8.dp)) {
            Text(
                text = ColorNaming.describe(preview).asString(),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = themedText(Color.White),
                style = TextStyle(shadow = PickerTextShadow),
                modifier = Modifier.weight(1f, fill = false).alignByBaseline(),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = hexOf(Color(preview)),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = themedSubText(Color.White.copy(alpha = 0.78f)),
                style = TextStyle(shadow = PickerTextShadow),
                modifier = Modifier.alignByBaseline(),
            )
        }
        if (showContrast) BackdropContrast(preview, menuText)
    }
}

/**
 * How well [menuText] reads on the background [waveArgb] paints: at the gradient's dark top and at
 * its bright bottom, each as a ratio and a verdict in words, with a mark that does not rely on colour.
 */
@Composable
internal fun BackdropContrast(waveArgb: Long, menuText: Color) {
    val (top, bottom) = lightBackgroundAnchors(waveArgb)
    Spacer(Modifier.height(4.dp))
    listOf(R.string.xmbu_color_scheme_contrast_top to top, R.string.xmbu_color_scheme_contrast_bottom to bottom).forEach { (lineRes, backdrop) ->
        val ratio = worstContrast(menuText, listOf(Color(backdrop)))
        val mark = when (ContrastVerdict.of(ratio)) {
            ContrastVerdict.EASY -> "✓"
            ContrastVerdict.READABLE -> "–"
            ContrastVerdict.HARD -> "⚠"
        }
        Text(
            text = stringResource(lineRes, mark, contrastReadout(ratio)),
            fontSize = 11.sp,
            color = themedSubText(Color.White.copy(alpha = 0.86f)),
            style = TextStyle(shadow = PickerTextShadow),
        )
    }
}

/**
 * One stepper row: its title, an arrow either side, and the step's name between them. With the pad
 * in use the title is the prompt itself — [hint]'s glyphs, then the word — so the buttons that step
 * the row are named on it rather than on a bar of their own, which cost the list a row and a half.
 */
@Composable
private fun ShadeStepper(
    title: String,
    hint: ControllerPromptItem?,
    value: String,
    step: Int,
    previousTag: String,
    nextTag: String,
    onStep: (Int) -> Unit,
) {
    val titleStyle = TextStyle(fontSize = 12.sp, shadow = PickerTextShadow)
    val titleColor = themedSubText(Color.White.copy(alpha = 0.78f))
    // The glyphs take room from the arrows, which the pad never taps; touch keeps the wide ones.
    val arrowWidth = if (hint != null) PadArrowWidth else TouchArrowWidth
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(32.dp)) {
        Box(Modifier.width(if (hint != null) HintedTitleWidth else PlainTitleWidth), contentAlignment = Alignment.CenterStart) {
            if (hint != null) {
                ControllerPromptBar(
                    items = listOf(hint),
                    labelColor = titleColor,
                    labelStyle = titleStyle,
                    glyphSize = HintGlyphSize,
                    arrangement = Arrangement.Start,
                )
            } else {
                Text(text = title, color = titleColor, style = titleStyle)
            }
        }
        StepArrow("◀", enabled = step > -XmbSchemeVariant.MAX_STEP, tag = previousTag, width = arrowWidth) { onStep(-1) }
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = themedText(Color.White),
            textAlign = TextAlign.Center,
            maxLines = 1,
            style = TextStyle(shadow = PickerTextShadow),
            modifier = Modifier.weight(1f),
        )
        StepArrow("▶", enabled = step < XmbSchemeVariant.MAX_STEP, tag = nextTag, width = arrowWidth) { onStep(+1) }
    }
}

private val PlainTitleWidth = 44.dp
private val HintedTitleWidth = 78.dp
private val HintGlyphSize = 15.dp
private val TouchArrowWidth = 40.dp
private val PadArrowWidth = 32.dp

/** An arrow dims at the end of its range but stays in place, so the row never shifts. */
@Composable
private fun StepArrow(glyph: String, enabled: Boolean, tag: String, width: Dp, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .testTag(tag)
            .size(width = width, height = 32.dp)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = glyph,
            fontSize = 13.sp,
            color = themedText(Color.White.copy(alpha = if (enabled) 0.95f else 0.28f)),
            style = TextStyle(shadow = PickerTextShadow),
        )
    }
}

@Composable
private fun ColorSchemeRow(
    option: ColorSchemeOption,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isSelected) {
                    Brush.horizontalGradient(
                        0f to Color.Transparent,
                        1f to Color.White.copy(alpha = 0.22f),
                    )
                } else {
                    Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Transparent)
                },
            )
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(option.swatch)),
            )
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    text = option.label.asString(),
                    fontSize = if (isSelected) 16.sp else 15.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = themedText(if (isSelected) Color.White else Color.White.copy(alpha = 0.66f)),
                    style = TextStyle(shadow = PickerTextShadow),
                )
                Text(
                    text = option.sublabel.asString(),
                    fontSize = 11.sp,
                    color = themedSubText(Color.White.copy(alpha = if (isSelected) 0.78f else 0.5f)),
                    style = TextStyle(shadow = PickerTextShadow),
                )
            }
        }
    }
}

/**
 * Thin adapter onto the shared [HsvColorPickerDialog]. This file used to carry its own copy of the
 * whole 440dp panel; only the state shape is XMB-specific now.
 */
@Composable
fun CustomColorPickerOverlay(
    state: HsvPickerState,
    onStateChange: (HsvPickerState) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    // The pad's glyph hints; off in touch mode, as the Settings callers of the dialog do.
    showHints: Boolean = true,
    menuText: Color = Color.White,
    showContrast: Boolean = true,
) {
    val readout: (@Composable () -> Unit)? =
        if (showContrast) { { BackdropContrast(state.argb, menuText) } } else null
    Box(modifier = modifier) {
        HsvColorPickerDialog(
            title = stringResource(R.string.xmbu_color_scheme_custom_title),
            state = state,
            onStateChange = onStateChange,
            onConfirm = onConfirm,
            onCancel = onCancel,
            showHints = showHints,
            // Docked beside the XMB it is repainting, so the colour is judged on the whole screen.
            docked = true,
            readout = readout,
        )
    }
}
