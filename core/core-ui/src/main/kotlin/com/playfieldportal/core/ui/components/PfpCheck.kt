package com.playfieldportal.core.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * PFP's checkmark, drawn as a path instead of typed as "✓".
 *
 * A glyph takes its shape, weight and vertical position from whichever font the device falls back
 * to and from the inherited line height, so it sat off-centre in every badge and box that used it.
 * A path looks the same on every device. Three shapes: the bare mark for list rows, [PfpCheckbox]
 * for toggles, and [PfpCheckBadge] for tiles.
 */

/** The bare mark, [size] square. [shadow] draws a 1 dp drop copy first, for marks over wallpaper. */
@Composable
fun PfpCheckMark(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    shadow: Color? = null,
) {
    Canvas(modifier.size(size)) {
        val path = checkPath(this.size)
        val stroke = Stroke(
            width = this.size.minDimension * STROKE_FRACTION,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round,
        )
        if (shadow != null) translate(top = 1.dp.toPx()) { drawPath(path, shadow, style = stroke) }
        drawPath(path, color, style = stroke)
    }
}

/** A square checkbox: filled with [color] and marked in [markColor] when checked, an outline when not. */
@Composable
fun PfpCheckbox(
    checked: Boolean,
    color: Color,
    markColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    val shape = RoundedCornerShape(size * 0.17f)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .then(
                if (checked) Modifier.background(color)
                else Modifier.border(size / 9, color.copy(alpha = color.alpha * 0.6f), shape)
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) PfpCheckMark(markColor, size = size * 0.78f)
    }
}

/** A round badge carrying the mark, for a picked tile's corner. */
@Composable
fun PfpCheckBadge(
    fill: Color,
    markColor: Color,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .background(fill, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        PfpCheckMark(markColor, size = size * 0.67f)
    }
}

// Proportions of the approved mockup (C19): a 24-unit box with the stroke through 5,12.5 → 9.5,17 → 19,7.5.
private fun checkPath(size: Size) = Path().apply {
    moveTo(size.width * 0.21f, size.height * 0.52f)
    lineTo(size.width * 0.40f, size.height * 0.71f)
    lineTo(size.width * 0.79f, size.height * 0.31f)
}

private const val STROKE_FRACTION = 0.14f

@Preview(name = "Checkmarks", backgroundColor = 0xFF0743A2, showBackground = true)
@Composable
private fun PfpCheckPreview() {
    Row(
        modifier = Modifier.padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PfpCheckBadge(fill = Color(0xFF7EE8FF), markColor = Color(0xFF0743A2))
        PfpCheckbox(checked = true, color = Color.White, markColor = Color(0xFF06224B))
        PfpCheckbox(checked = false, color = Color.White, markColor = Color(0xFF06224B))
        PfpCheckbox(checked = true, color = Color.White, markColor = Color(0xFF06224B), size = 13.dp)
        PfpCheckMark(Color.White, size = 15.dp, shadow = Color.Black.copy(alpha = 0.75f))
    }
}
