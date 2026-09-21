package com.playfieldportal.feature.xmb.ui.photo

import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private val PHOTO_STEP_COMMIT_DP = 72.dp
private const val FLING_DP_PER_S = 420f

/**
 * One detector owns taps, navigation, pinch zoom and zoomed panning.
 *
 * The zoom state and the sensitivity scale feed this through stable holders and are read *inside*
 * the gesture, never used as `pointerInput` keys. `zoomed` flips the instant a pinch crosses
 * `ZOOM_MIN`, and a key change cancels the running detector — whose replacement immediately waits
 * for a fresh pointer-down, so the rest of that pinch was discarded and the image stopped scaling
 * until the fingers came off. Read live, one pinch both enters the zoom and keeps driving it, and a
 * touch-sensitivity change is picked up by the next swipe rather than by rebuilding the detector.
 */
@Composable
fun Modifier.photoViewerGestures(
    zoomed: Boolean,
    stepScale: Float,
    onTap: () -> Unit,
    onStep: (Int) -> Unit,
    onTransform: (zoom: Float, panX: Float, panY: Float) -> Unit,
): Modifier {
    val zoomedNow = rememberUpdatedState(zoomed)
    val stepScaleNow = rememberUpdatedState(stepScale)
    return photoViewerGestureDetector(
        zoomed = { zoomedNow.value },
        stepScale = { stepScaleNow.value },
        onTap = onTap,
        onStep = onStep,
        onTransform = onTransform,
    )
}

/**
 * Installs the detector. Both live inputs arrive as suppliers rather than values, because they have
 * to be read inside the gesture: as parameters they would key the `pointerInput` and cut off
 * whatever gesture was running when they changed.
 */
private fun Modifier.photoViewerGestureDetector(
    zoomed: () -> Boolean,
    stepScale: () -> Float,
    onTap: () -> Unit,
    onStep: (Int) -> Unit,
    onTransform: (zoom: Float, panX: Float, panY: Float) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val slop = viewConfiguration.touchSlop
            val horizontalCommitPx = PHOTO_STEP_COMMIT_DP.toPx() * stepScale()
            val flingPxPerSecond = FLING_DP_PER_S * density
            val down = awaitFirstDown(requireUnconsumed = false)
            val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
            var axis = Axis.NONE
            var accumulatedX = 0f
            var lockX = 0f
            var lockY = 0f
            var transforming = false
            var activePointers = setOf(down.id)

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id }
                if (change == null || !change.pressed) break

                val pressed = event.changes.filter { it.pressed }.mapTo(mutableSetOf()) { it.id }
                if (pressed.size >= 2) {
                    transforming = true
                    axis = Axis.CANCELLED
                    activePointers = pressed
                    val zoom = event.calculateZoom()
                    val pan = event.calculatePan()
                    if (zoom != 1f || pan != androidx.compose.ui.geometry.Offset.Zero) {
                        onTransform(zoom, pan.x, pan.y)
                    }
                    event.changes.forEach(PointerInputChange::consume)
                    continue
                }
                if (transforming || axis == Axis.CANCELLED) {
                    activePointers = pressed
                    if (pressed.isEmpty()) break
                    change.consume()
                    continue
                }
                if (activePointers.size > pressed.size) {
                    activePointers = pressed
                    if (pressed.isEmpty()) break
                }

                val delta = change.positionChange()
                tracker.addPosition(change.uptimeMillis, change.position)
                if (axis == Axis.NONE) {
                    lockX += delta.x
                    lockY += delta.y
                    if (abs(lockX) > slop || abs(lockY) > slop) {
                        axis = if (abs(lockX) >= abs(lockY)) Axis.HORIZONTAL else Axis.VERTICAL
                        accumulatedX = lockX
                        change.consume()
                    }
                } else {
                    if (zoomed()) {
                        onTransform(1f, delta.x, delta.y)
                    } else if (axis == Axis.HORIZONTAL) {
                        accumulatedX += delta.x
                    }
                    change.consume()
                }
            }

            if (transforming || axis == Axis.CANCELLED) continue
            if (axis == Axis.NONE) {
                onTap()
                continue
            }
            if (zoomed() || axis != Axis.HORIZONTAL) continue

            val velocityX = tracker.calculateVelocity().x
            photoStepFromSwipe(
                accumulatedX,
                horizontalCommitPx,
                velocityX,
                flingPxPerSecond,
            ).takeIf { it != 0 }?.let(onStep)
        }
    }
}

// A vertical drag at fit is a deliberate dead end: it locks the axis so it cannot step photos,
// and commits nothing.
private enum class Axis { NONE, HORIZONTAL, VERTICAL, CANCELLED }

/** Returns one step only: left is next (+1), right is previous (-1). */
fun photoStepFromSwipe(
    accumulatedX: Float,
    commitPx: Float,
    velocityPxPerS: Float,
    flingPx: Float,
): Int {
    val committedByDistance = abs(accumulatedX) >= commitPx
    val committedByFling = abs(velocityPxPerS) >= flingPx
    if (!committedByDistance && !committedByFling) return 0
    // Travel is the gesture's direction. A fling only supplies the missing commit signal; it
    // must not reverse a short drag whose release velocity happens to point the other way.
    val direction = when {
        accumulatedX != 0f -> accumulatedX
        velocityPxPerS != 0f -> velocityPxPerS
        else -> 0f
    }
    return when {
        direction < 0f -> 1
        direction > 0f -> -1
        else -> 0
    }
}
