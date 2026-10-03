package com.playfieldportal.feature.xmb.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The lifted look of anything being moved: a white outline [pad] outside [icon] with a chevron set
 * into each of the two edges it can travel across. A row in a column moves up and down, so its
 * chevrons sit in the top and bottom edges; a category on the crossbar moves left and right
 * ([horizontal]), so its chevrons sit in the left and right edges.
 *
 * The chevrons sit ON the outline, never beyond it, so nothing reaches past the frame into a label
 * or a neighbour, and the border is cut where each one crosses it so it reads as a handle set into
 * the frame rather than an arrow drawn over a line.
 */
internal fun DrawScope.drawMoveOutline(
    icon: Rect,
    corner: Dp,
    horizontal: Boolean = false,
    pad: Dp = 4.dp,
) {
    val padPx = pad.toPx()
    val frame = Rect(icon.left - padPx, icon.top - padPx, icon.right + padPx, icon.bottom + padPx)
    val half = 8.dp.toPx()
    val rise = 7.dp.toPx()
    val notch = 4.dp.toPx()

    val notches = Path().apply {
        if (horizontal) {
            for (edge in listOf(frame.left, frame.right)) {
                addRect(Rect(edge - rise, frame.center.y - half - notch, edge + rise, frame.center.y + half + notch))
            }
        } else {
            for (edge in listOf(frame.top, frame.bottom)) {
                addRect(Rect(frame.center.x - half - notch, edge - rise, frame.center.x + half + notch, edge + rise))
            }
        }
    }
    clipPath(notches, ClipOp.Difference) {
        drawRoundRect(
            color = Color.White,
            topLeft = frame.topLeft,
            size = frame.size,
            cornerRadius = CornerRadius(corner.toPx()),
            style = Stroke(width = 2.dp.toPx()),
        )
    }

    val chevron = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    // Each chevron points out of the frame, its tip half a rise past the edge.
    fun point(edge: Float, outward: Float): Path = Path().apply {
        if (horizontal) {
            val y = frame.center.y
            moveTo(edge - outward * rise / 2f, y - half)
            lineTo(edge + outward * rise / 2f, y)
            lineTo(edge - outward * rise / 2f, y + half)
        } else {
            val x = frame.center.x
            moveTo(x - half, edge - outward * rise / 2f)
            lineTo(x, edge + outward * rise / 2f)
            lineTo(x + half, edge - outward * rise / 2f)
        }
    }
    if (horizontal) {
        drawPath(point(frame.left, -1f), Color.White, style = chevron)
        drawPath(point(frame.right, +1f), Color.White, style = chevron)
    } else {
        drawPath(point(frame.top, -1f), Color.White, style = chevron)
        drawPath(point(frame.bottom, +1f), Color.White, style = chevron)
    }
}

