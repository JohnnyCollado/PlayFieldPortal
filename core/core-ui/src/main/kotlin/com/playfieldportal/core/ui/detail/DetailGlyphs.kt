package com.playfieldportal.core.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

// Small marks drawn as paths rather than typed as characters. A glyph takes its shape, weight and
// baseline from whichever font the device falls back to — the same reason PfpCheckMark exists — and
// these three sit inside tight chrome where that drift is visible.

/** The favorite star, for badges and rows. */
@Composable
fun PfpStarMark(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
) {
    Canvas(modifier.size(size)) {
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val outer = this.size.minDimension / 2f
        val inner = outer * 0.44f
        val path = Path()
        repeat(10) { i ->
            // Alternate outer/inner radius, starting straight up (-90°), for a five-pointed star.
            val radius = if (i % 2 == 0) outer else inner
            val angle = Math.toRadians((-90.0 + i * 36.0))
            val x = cx + (radius * cos(angle)).toFloat()
            val y = cy + (radius * sin(angle)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, color)
    }
}

/** The disclosure chevron shown at a row's trailing edge — "Confirm opens more". */
@Composable
fun PfpChevronMark(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.34f, h * 0.16f)
            lineTo(w * 0.68f, h * 0.5f)
            lineTo(w * 0.34f, h * 0.84f)
        }
        drawPath(
            path,
            color,
            style = Stroke(width = w * 0.13f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/**
 * The "unavailable" mark: an outlined circle with a diagonal slash.
 *
 * Availability must never be carried by colour alone, and a greyed label still reads as present;
 * a slash through the action's own slot says "this exists and cannot be used" in any theme.
 */
@Composable
fun PfpBlockedMark(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
) {
    Canvas(modifier.size(size)) {
        val stroke = this.size.minDimension * 0.11f
        val inset = stroke / 2f
        val diameter = this.size.minDimension - stroke
        drawCircle(
            color = color,
            radius = diameter / 2f,
            center = Offset(this.size.width / 2f, this.size.height / 2f),
            style = Stroke(width = stroke),
        )
        drawLine(
            color = color,
            start = Offset(inset, this.size.height - inset),
            end = Offset(this.size.width - inset, inset),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

/** The media-tile play mark. */
@Composable
fun PfpPlayMark(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.30f, h * 0.20f)
            lineTo(w * 0.80f, h * 0.50f)
            lineTo(w * 0.30f, h * 0.80f)
        }
        path.close()
        drawPath(path, color)
    }
}
