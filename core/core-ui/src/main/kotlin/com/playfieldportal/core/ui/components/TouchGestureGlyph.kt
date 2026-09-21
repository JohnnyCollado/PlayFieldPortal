package com.playfieldportal.core.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.domain.model.TouchGesture
import com.playfieldportal.core.ui.R

@DrawableRes
fun TouchGesture.drawableFor(): Int = when (this) {
    TouchGesture.TAP -> R.drawable.ctl_touch_tap
    TouchGesture.LONG_PRESS -> R.drawable.ctl_touch_long_press
    TouchGesture.SWIPE_LEFT -> R.drawable.ctl_touch_swipe_left
    TouchGesture.SWIPE_RIGHT -> R.drawable.ctl_touch_swipe_right
    TouchGesture.SWIPE_DOWN -> R.drawable.ctl_touch_swipe_down
}

@Composable
fun TouchGestureGlyph(
    gesture: TouchGesture,
    modifier: Modifier = Modifier,
    size: Dp = 22.dp,
) {
    Image(
        painter = painterResource(gesture.drawableFor()),
        contentDescription = null,
        modifier = modifier.size(size).clearAndSetSemantics { },
    )
}

@Immutable
data class TouchPromptItem(
    val gestures: List<TouchGesture>,
    val label: String,
) {
    constructor(gesture: TouchGesture, label: String) : this(listOf(gesture), label)
}

@Composable
fun TouchPromptBar(
    items: List<TouchPromptItem>,
    modifier: Modifier = Modifier,
    labelColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.75f),
    labelStyle: androidx.compose.ui.text.TextStyle = androidx.compose.ui.text.TextStyle.Default,
    glyphSize: Dp = 22.dp,
    arrangement: Arrangement.Horizontal = Arrangement.spacedBy(28.dp, androidx.compose.ui.Alignment.CenterHorizontally),
) {
    Row(modifier = modifier, horizontalArrangement = arrangement) {
        for (item in items) {
            PromptRow(
                glyphs = item.gestures,
                label = item.label,
                labelColor = labelColor,
                labelStyle = labelStyle,
                glyphSize = glyphSize,
                glyph = { gesture -> TouchGestureGlyph(gesture, size = glyphSize) },
            )
        }
    }
}

@Composable
internal fun <T> PromptRow(
    glyphs: List<T>,
    label: String,
    modifier: Modifier = Modifier,
    labelColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.75f),
    labelStyle: androidx.compose.ui.text.TextStyle = androidx.compose.ui.text.TextStyle.Default,
    glyphSize: Dp = 22.dp,
    spacing: Dp = 4.dp,
    glyphSpacing: Dp = 2.dp,
    glyph: @Composable (T) -> Unit,
) {
    if (glyphs.isEmpty()) return
    Row(
        modifier = modifier,
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(glyphSpacing)) {
            for (item in glyphs) glyph(item)
        }
        Text(text = label, color = labelColor, style = labelStyle)
    }
}
