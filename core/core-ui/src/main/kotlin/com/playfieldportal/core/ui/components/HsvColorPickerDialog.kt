package com.playfieldportal.core.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.ui.theme.contrastRatio

/**
 * The app's one HSV colour picker.
 *
 * There used to be two: the XMB's `CustomColorPickerOverlay` and Themes' private
 * `IconColorCustomPicker`. They were the same 440dp panel, the same `0xFF15151F` surface, the same
 * three channel bars and the same gamepad hint, drifting apart one small edit at a time. Callers
 * differ only in the title, the accent/subtext colours, and whether they want the contrast strip.
 *
 * Channel indices are the caller's contract with its own state: 0 = hue, 1 = saturation,
 * 2 = brightness. The dialog is stateless — it renders the values it is given and reports
 * fractions back — because both call sites already drive channel selection from the D-pad.
 */
@Composable
fun HsvColorPickerDialog(
    title: String,
    hue: Float,
    saturation: Float,
    brightness: Float,
    selectedChannel: Int,
    onChannelFraction: (channel: Int, fraction: Float) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    accent: Color = Color.White,
    subtext: Color = Color.White.copy(alpha = 0.7f),
    /**
     * Darkest and brightest backdrop the picked colour will actually sit on. When supplied, the
     * dialog shows live contrast readings against both — which is what turns a later automatic
     * adjustment from a surprise into something the user already saw coming.
     */
    contrastAnchors: Pair<Color, Color>? = null,
    /** Ratio below which a reading is called out. 3:1 by decision — see the legibility plan. */
    contrastWarnBelow: Float = 3f,
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
                // Swallows taps so a click inside the panel does not reach the scrim's cancel.
                .clickable(onClick = {})
                .padding(24.dp),
        ) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
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
                Text(hexOf(preview), color = subtext, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
            }

            if (contrastAnchors != null) {
                Spacer(Modifier.height(16.dp))
                ContrastStrip(preview, contrastAnchors.first, contrastAnchors.second, subtext, contrastWarnBelow)
            }

            Spacer(Modifier.height(20.dp))
            ChannelBar(
                label = "Hue",
                fraction = hue / 360f,
                brush = rainbowBrush(),
                selected = selectedChannel == 0,
                accent = accent,
                subtext = subtext,
                onFraction = { onChannelFraction(0, it) },
            )
            Spacer(Modifier.height(14.dp))
            ChannelBar(
                label = "Saturation",
                fraction = saturation,
                brush = Brush.horizontalGradient(
                    listOf(hsvColor(hue, 0f, brightness), hsvColor(hue, 1f, brightness)),
                ),
                selected = selectedChannel == 1,
                accent = accent,
                subtext = subtext,
                onFraction = { onChannelFraction(1, it) },
            )
            Spacer(Modifier.height(14.dp))
            ChannelBar(
                label = "Brightness",
                fraction = brightness,
                brush = Brush.horizontalGradient(
                    listOf(hsvColor(hue, saturation, 0f), hsvColor(hue, saturation, 1f)),
                ),
                selected = selectedChannel == 2,
                accent = accent,
                subtext = subtext,
                onFraction = { onChannelFraction(2, it) },
            )
            Spacer(Modifier.height(20.dp))
            Text("◄ ► adjust    ▲ ▼ channel    Ⓐ apply    Ⓑ cancel", color = subtext, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Apply",
                    color = accent,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable(onClick = onConfirm).padding(vertical = 6.dp, horizontal = 10.dp),
                )
                Text(
                    "Cancel",
                    color = subtext,
                    fontSize = 15.sp,
                    modifier = Modifier.clickable(onClick = onCancel).padding(vertical = 6.dp, horizontal = 10.dp),
                )
            }
        }
    }
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
    onFraction: (Float) -> Unit,
) {
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
fun ColorSwatch(
    label: String,
    fill: Color?,
    brush: Brush?,
    selected: Boolean,
    focused: Boolean,
    accent: Color,
    subtext: Color,
    onClick: () -> Unit,
) {
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
                .clickable(onClick = onClick),
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

fun rainbowBrush(): Brush = Brush.horizontalGradient(
    listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red),
)

fun hsvColor(hue: Float, saturation: Float, brightness: Float): Color =
    Color(
        android.graphics.Color.HSVToColor(
            floatArrayOf(hue, saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f)),
        ),
    )

fun hsvToArgbLong(hue: Float, saturation: Float, brightness: Float): Long =
    android.graphics.Color
        .HSVToColor(floatArrayOf(hue, saturation.coerceIn(0f, 1f), brightness.coerceIn(0f, 1f)))
        .toLong() and 0xFFFFFFFFL

fun hexOf(color: Color): String = String.format("#%06X", 0xFFFFFF and color.toArgb())
