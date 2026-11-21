package com.playfieldportal.core.ui.components

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.ui.motion.LocalMotionAllowed
import com.playfieldportal.core.ui.theme.themedText

/** Frames in one turn of the busy ring. */
const val BUSY_RING_FRAMES = 15

/** Milliseconds for one full turn. */
const val BUSY_RING_TURN_MILLIS = 900L

private const val FRAME_MILLIS = BUSY_RING_TURN_MILLIS / BUSY_RING_FRAMES
private const val RING_SWEEP_DEGREES = 290f

/** Rotation of the ring's gap, in degrees, at [frame]; frames wrap and negatives fold back. */
fun busyRingAngleForFrame(frame: Int): Float =
    Math.floorMod(frame, BUSY_RING_FRAMES) * (360f / BUSY_RING_FRAMES)

/** The frame showing [elapsedMillis] after the ring started; time before the start counts as 0. */
fun busyRingFrameForElapsed(elapsedMillis: Long): Int =
    ((elapsedMillis.coerceAtLeast(0L) / FRAME_MILLIS) % BUSY_RING_FRAMES).toInt()

/** Stroke width as a share of the ring's size. */
fun busyRingStrokeFraction(): Float = 0.11f

/**
 * PFP's own indeterminate busy indicator: a ring with a gap that turns in [BUSY_RING_FRAMES]
 * discrete steps, drawn in [color] with a shadow twin. It holds still on a static ring when
 * motion is off (system animator scale 0, or [LocalMotionAllowed] false).
 */
@Composable
fun PfpBusyRing(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    color: Color = themedText(Color.White),
    contentDescription: String? = null,
) {
    val context = LocalContext.current
    val systemOff = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val animate = LocalMotionAllowed.current && !systemOff
    var frame by remember { mutableIntStateOf(0) }
    LaunchedEffect(animate) {
        if (!animate) {
            frame = 0
            return@LaunchedEffect
        }
        var start = -1L
        while (true) {
            withFrameMillis { now ->
                if (start < 0) start = now
                val next = busyRingFrameForElapsed(now - start)
                if (next != frame) frame = next
            }
        }
    }
    Canvas(
        modifier
            .size(size)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate
                if (contentDescription != null) this.contentDescription = contentDescription
            },
    ) {
        val stroke = this.size.minDimension * busyRingStrokeFraction()
        val twin = ShadowTwinOffset.toPx()
        val inset = stroke / 2f
        val diameter = this.size.minDimension - stroke - twin
        val arcSize = Size(diameter, diameter)
        val start = busyRingAngleForFrame(frame) - 90f
        val style = Stroke(width = stroke, cap = StrokeCap.Round)
        drawArc(shadowTwinColor(color), start, RING_SWEEP_DEGREES, false, Offset(inset + twin, inset + twin), arcSize, style = style)
        drawArc(color, start, RING_SWEEP_DEGREES, false, Offset(inset, inset), arcSize, style = style)
    }
}
