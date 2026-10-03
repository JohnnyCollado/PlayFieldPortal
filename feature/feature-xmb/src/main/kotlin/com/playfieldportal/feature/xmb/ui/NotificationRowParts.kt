package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.NotificationDetail
import com.playfieldportal.core.domain.model.ResultOutcome
import com.playfieldportal.core.ui.components.resultOutcomeColor

// The two approved row marks (notification details plan §6.1, artboard 1 of the mockups): a page
// with lines on a Notes row, and a tally of failures and skips on a Results row.

/** 14dp page-with-lines outline at 62% white: this row opens a Notes sheet. */
@Composable
internal fun NotesMark(modifier: Modifier = Modifier) {
    val tint = Color.White.copy(alpha = 0.62f)
    Canvas(modifier.size(14.dp)) {
        val u = size.minDimension / 24f
        val stroke = Stroke(width = 1.8f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val page = Path().apply {
            moveTo(6 * u, 3 * u)
            lineTo(14 * u, 3 * u)
            lineTo(18 * u, 7 * u)
            lineTo(18 * u, 21 * u)
            lineTo(6 * u, 21 * u)
            close()
        }
        drawPath(page, tint, style = stroke)
        listOf(11f to 15f, 14.5f to 15f, 18f to 13f).forEach { (y, end) ->
            drawLine(tint, Offset(9 * u, y * u), Offset(end * u, y * u), strokeWidth = 1.8f * u, cap = StrokeCap.Round)
        }
    }
}

/**
 * An 18dp pill with one dot-and-count per non-empty FAILED / SKIPPED bucket; nothing for a
 * Results row where everything succeeded. Dimmer on a read row, like the row's title.
 */
@Composable
internal fun TallyChip(results: NotificationDetail.Results, read: Boolean, modifier: Modifier = Modifier) {
    val segments = listOf(ResultOutcome.FAILED, ResultOutcome.SKIPPED)
        .map { it to results.count(it) }
        .filter { it.second > 0 }
    if (segments.isEmpty()) return
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier = modifier
            .height(18.dp)
            .clip(shape)
            .background(Color.White.copy(alpha = if (read) 0.06f else 0.08f))
            .border(1.dp, Color.White.copy(alpha = if (read) 0.12f else 0.16f), shape)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        segments.forEach { (outcome, count) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(resultOutcomeColor(outcome)))
                Text(
                    text = count.toString(),
                    color = Color.White.copy(alpha = if (read) 0.70f else 0.88f),
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
    }
}
