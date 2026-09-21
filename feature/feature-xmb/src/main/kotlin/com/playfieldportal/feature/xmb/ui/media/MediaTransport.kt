package com.playfieldportal.feature.xmb.ui.media

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// The transport vocabulary shared by the built-in media players — the scrub bar, its cursor, the
// symbol buttons and the clock. Extracted from the video player so the music player reads as the
// same instrument rather than a second one built to the same brief.

/** Bar visual is 4dp; the target around it has to be a finger wide. */
private val SCRUBBER_TOUCH_HEIGHT = 28.dp

/** Played fraction of [durationMs], safe for the not-yet-prepared case where the duration is 0. */
fun mediaScrubFraction(positionMs: Long, durationMs: Long): Float =
    if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

/** Position a scrub [fraction] stands for, clamped into the track. */
fun mediaScrubPositionMs(fraction: Float, durationMs: Long): Long =
    (fraction.coerceIn(0f, 1f) * durationMs.coerceAtLeast(0L)).toLong()

/** `m:ss`, or `h:mm:ss` once past the hour. */
fun formatMediaTime(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * The bottom-band scrub row: bar, cursor and `position / duration` clock.
 *
 * Owns the drag itself. While a finger is down the bar and the clock follow it rather than the
 * player, and the seek is committed once on release — seeking a decoder on every pixel of travel
 * stutters it for the whole length of the drag. [seekable] is false for a pad (which seeks in
 * fixed steps from the D-pad) and until the duration is known, and the cursor then invites no
 * dragging it cannot honour.
 */
@Composable
fun MediaScrubBar(
    positionMs: Long,
    durationMs: Long,
    color: Color,
    seekable: Boolean,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    timeColor: Color = Color.White,
    /**
     * False drops the inline `position / duration` label and leaves the bar alone. The music
     * player prints the two halves stacked above the bar in different colours, which is the PSP
     * Visual Player's treatment; the video player keeps the inline clock.
     */
    showClock: Boolean = true,
) {
    var scrubFraction by remember { mutableStateOf<Float?>(null) }
    val shownFraction = scrubFraction ?: mediaScrubFraction(positionMs, durationMs)
    val shownMs = scrubFraction?.let { mediaScrubPositionMs(it, durationMs) } ?: positionMs

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Scrubber(
            fraction = shownFraction,
            color = color,
            scrubbing = scrubFraction != null,
            modifier = Modifier
                .weight(1f)
                .then(
                    if (!seekable || durationMs <= 0) Modifier else Modifier
                        .pointerInput(durationMs) {
                            detectTapGestures { offset ->
                                onSeekTo(mediaScrubPositionMs(offset.x / size.width, durationMs))
                            }
                        }
                        .pointerInput(durationMs) {
                            detectHorizontalDragGestures(
                                onDragStart = { offset ->
                                    scrubFraction = (offset.x / size.width).coerceIn(0f, 1f)
                                },
                                onDragEnd = {
                                    scrubFraction?.let { onSeekTo(mediaScrubPositionMs(it, durationMs)) }
                                    scrubFraction = null
                                },
                                onDragCancel = { scrubFraction = null },
                            ) { change, _ ->
                                scrubFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                                change.consume()
                            }
                        }
                ),
        )
        if (showClock) {
            Spacer(Modifier.width(12.dp))
            Text(
                "${formatMediaTime(shownMs)} / ${formatMediaTime(durationMs)}",
                color = timeColor,
                fontSize = 13.sp,
            )
        }
    }
}

/**
 * Progress bar plus its cursor, drawn in one pass so the thumb cannot drift off the fill.
 *
 * The canvas is [SCRUBBER_TOUCH_HEIGHT] tall for a 4dp bar: the visual weight is the reference's, but a
 * 4dp drag target is unhittable. The cursor's centre is clamped inside the track by its own
 * radius, so it never hangs half-off either end.
 */
@Composable
fun Scrubber(
    fraction: Float,
    color: Color,
    scrubbing: Boolean,
    modifier: Modifier = Modifier,
) {
    val radius by animateDpAsState(
        targetValue = if (scrubbing) 9.dp else 6.dp,
        label = "scrubberThumb",
    )
    Canvas(modifier.height(SCRUBBER_TOUCH_HEIGHT)) {
        val centreY = size.height / 2f
        val barHeight = 4.dp.toPx()
        val corner = CornerRadius(barHeight / 2f)
        val top = Offset(0f, centreY - barHeight / 2f)

        drawRoundRect(color.copy(alpha = 0.28f), top, Size(size.width, barHeight), corner)
        drawRoundRect(color, top, Size(size.width * fraction, barHeight), corner)

        val thumb = radius.toPx()
        val x = (size.width * fraction).coerceIn(thumb, (size.width - thumb).coerceAtLeast(thumb))
        // A dark ring under the cursor for the same reason the symbols carry one: this band has
        // a scrim, but the cursor sits at the bright end of it.
        drawCircle(Color.Black.copy(alpha = 0.35f), thumb + 1.5.dp.toPx(), Offset(x, centreY))
        drawCircle(color, thumb, Offset(x, centreY))
    }
}

/**
 * One transport target: the symbol alone, no ring and no fill.
 *
 * Material vectors rather than Text glyphs - U+23EE/23EA/23E9/23ED/23F8 all carry
 * Emoji_Presentation, so a typed symbol falls back to the colour emoji font on most Android
 * builds and the row comes out as blue-and-white emoji buttons. feature-xmb already carries
 * material-icons-extended, which is where Replay10 / Forward10 come from.
 *
 * [size] is the touch target, which the symbol alone would undersize, and stays 44dp at minimum.
 */
@Composable
fun TransportButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    size: Dp = 44.dp,
    iconSize: Dp = 26.dp,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .alpha(if (enabled) 1f else 0.35f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}
