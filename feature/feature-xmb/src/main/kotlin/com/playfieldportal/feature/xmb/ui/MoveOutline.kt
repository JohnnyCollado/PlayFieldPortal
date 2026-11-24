package com.playfieldportal.feature.xmb.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
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
 * The lifted look of anything being moved: a white outline around [frame] with a chevron set into
 * each of the two edges it can travel across. A row in a column moves up and down, so its chevrons
 * sit in the top and bottom edges; a category on the crossbar moves left and right ([horizontal]),
 * so its chevrons sit in the left and right edges.
 *
 * The frame hugs the icon as it is drawn and nothing else: a game's tile, a card's square, a
 * category's icon. The name is not part of what is lifted. The chevrons sit INSIDE the frame with
 * their tips on the edge, never beyond it, so nothing reaches past the frame into a label or a
 * neighbour, and the border is cut where each one crosses it so it reads as a handle set into the
 * frame rather than an arrow drawn over a line.
 */
internal fun DrawScope.drawMoveOutline(
    frame: Rect,
    corner: Dp,
    horizontal: Boolean = false,
) {
    val half = 8.dp.toPx()
    val depth = 6.dp.toPx()
    val notch = 4.dp.toPx()
    val borderWidth = 2.dp.toPx()

    val notches = Path().apply {
        for (atStart in listOf(true, false)) {
            addRect(moveNotch(frame, horizontal, atStart, half, notch, borderWidth + 2.dp.toPx()))
        }
    }
    clipPath(notches, ClipOp.Difference) {
        drawRoundRect(
            color = Color.White,
            topLeft = frame.topLeft,
            size = frame.size,
            cornerRadius = CornerRadius(corner.toPx()),
            style = Stroke(width = borderWidth),
        )
    }

    val chevron = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    for (atStart in listOf(true, false)) {
        val (a, tip, b) = moveChevron(frame, horizontal, atStart, half, depth)
        drawPath(
            Path().apply {
                moveTo(a.x, a.y)
                lineTo(tip.x, tip.y)
                lineTo(b.x, b.y)
            },
            Color.White,
            style = chevron,
        )
    }
}

/** A frame [pad] outside [icon] on every side: the outline of a lifted icon-sized thing. */
internal fun moveIconFrame(icon: Rect, pad: Float): Rect =
    Rect(icon.left - pad, icon.top - pad, icon.right + pad, icon.bottom + pad)

/**
 * Where a list row's icon is drawn inside its icon box, in the box's own pixels. A wide box is a
 * game tile followed by the gap before its title ([textGap]), so the icon is the box less that
 * gap; anything else is a square glyph, card or app icon centred in the icon slot. The gap is taken
 * off before comparing because a slot is wider than it is tall too, only not by as much.
 */
internal fun leadingIconBounds(boxWidth: Float, boxHeight: Float, textGap: Float): Rect {
    val isTile = boxWidth - textGap > boxHeight * 1.5f
    val iconWidth = if (isTile) boxWidth - textGap else boxHeight
    val left = if (isTile) 0f else (boxWidth - iconWidth) / 2f
    return Rect(left, 0f, left + iconWidth, boxHeight)
}

/**
 * The three points of the chevron set into one edge of [frame], pointing out of it: its
 * tip lies on the edge and its wings [depth] inside it, [half] either side of the edge's middle.
 * [atStart] picks the top (or left, when [horizontal]) edge, otherwise the bottom (or right).
 */
internal fun moveChevron(frame: Rect, horizontal: Boolean, atStart: Boolean, half: Float, depth: Float): List<Offset> {
    val sign = if (atStart) 1f else -1f // wings sit this way (inward) from the edge
    return if (horizontal) {
        val edge = if (atStart) frame.left else frame.right
        val y = frame.center.y
        listOf(Offset(edge + sign * depth, y - half), Offset(edge, y), Offset(edge + sign * depth, y + half))
    } else {
        val edge = if (atStart) frame.top else frame.bottom
        val x = frame.center.x
        listOf(Offset(x - half, edge + sign * depth), Offset(x, edge), Offset(x + half, edge + sign * depth))
    }
}

/**
 * The cut in the border behind a chevron: [half] + [notch] either side of the edge's middle, and
 * [stroke] either side of the edge across it, so only the border near the handle is removed.
 */
internal fun moveNotch(frame: Rect, horizontal: Boolean, atStart: Boolean, half: Float, notch: Float, stroke: Float): Rect =
    if (horizontal) {
        val edge = if (atStart) frame.left else frame.right
        Rect(edge - stroke, frame.center.y - half - notch, edge + stroke, frame.center.y + half + notch)
    } else {
        val edge = if (atStart) frame.top else frame.bottom
        Rect(frame.center.x - half - notch, edge - stroke, frame.center.x + half + notch, edge + stroke)
    }
