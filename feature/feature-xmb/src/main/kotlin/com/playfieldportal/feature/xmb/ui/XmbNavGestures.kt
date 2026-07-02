package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

// ── Tuning ─────────────────────────────────────────────────────────────────────
// One vertical "step" of travel ≈ one item row. Kept independent of the list's ROW_HEIGHT so the
// gesture stays comfortable even though the list itself is a fixed cross (not a scroll surface).
private val STEP_DISTANCE_DP = 72.dp
// Left-edge band where a rightward drag means Back (matches the prior edge-swipe behaviour).
private val EDGE_DP = 32.dp
// Fling speed (dp/s) at/above which a short swipe still commits, and which grants extra skipped rows.
private val FLING_DP_PER_S = 420f
// A single swipe never skips more than this many item rows, so touch stays deliberate (never a fling).
const val MAX_ITEM_SKIP = 4

/**
 * The XMB home-screen touch gesture layer. A single axis-locked, single-pointer detector that
 * translates a drag into the SAME discrete navigation the D-pad produces — never a free scroll:
 *
 *  - horizontal drag  → [onStepCategory] (±1), or [onEdgeBack] when it starts at the left edge;
 *  - vertical drag    → [onStepItem] (±N, N capped by [MAX_ITEM_SKIP], from distance + velocity).
 *
 * Taps are left to the rows' own click handlers (a tap never exceeds touch-slop, so this detector
 * ignores it). Multi-touch is ignored (the XMB is a single cursor). Replaces the old
 * horizontal-only block in XMBShell; guarding (overlays, drill-lock) lives in the ViewModel intents.
 */
fun Modifier.xmbNavGestures(
    onStepCategory: (Int) -> Unit,
    onStepItem: (Int) -> Unit,
    onEdgeBack: () -> Unit,
): Modifier = pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    val stepPx = STEP_DISTANCE_DP.toPx()
    val edgePx = EDGE_DP.toPx()
    val flingPx = FLING_DP_PER_S * density   // dp/s → px/s
    val commitPx = stepPx * 0.6f

    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false)
            val startX = down.position.x
            var axis = Axis.NONE
            var accX = 0f
            var accY = 0f
            val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }

            while (true) {
                val event = awaitPointerEvent()
                // Second finger down → abandon (single-cursor model; e.g. an accidental two-finger drag).
                if (event.changes.count { it.pressed } > 1) { axis = Axis.CANCELLED }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break                       // pointer up → gesture ends
                if (axis == Axis.CANCELLED) { change.consume(); continue }

                val d = change.positionChange()
                accX += d.x; accY += d.y
                tracker.addPosition(change.uptimeMillis, change.position)

                if (axis == Axis.NONE && (abs(accX) > slop || abs(accY) > slop)) {
                    axis = if (abs(accX) >= abs(accY)) Axis.HORIZONTAL else Axis.VERTICAL
                }
                if (axis == Axis.HORIZONTAL || axis == Axis.VERTICAL) change.consume()
            }

            when (axis) {
                Axis.HORIZONTAL -> {
                    val vx = tracker.calculateVelocity().x
                    when {
                        // Rightward drag from the left edge → Back.
                        startX <= edgePx && accX > commitPx -> onEdgeBack()
                        accX <= -commitPx || vx <= -flingPx -> onStepCategory(1)   // left → next
                        accX >= commitPx || vx >= flingPx   -> onStepCategory(-1)  // right → previous
                    }
                }
                Axis.VERTICAL -> {
                    val steps = verticalSteps(accY, tracker.calculateVelocity().y, stepPx, flingPx)
                    if (steps != 0) onStepItem(steps)
                }
                else -> Unit   // NONE (a tap / too small) or CANCELLED
            }
        }
    }
}

private enum class Axis { NONE, HORIZONTAL, VERTICAL, CANCELLED }

/**
 * Pure step-count math (extracted for unit testing). Converts a vertical drag [distancePx] and its
 * release [velocityPxPerS] into a signed number of item steps: **up-swipe → positive (move DOWN the
 * list)**. Returns 0 when the drag is too small and too slow to commit. Magnitude grows with
 * distance and gets a small fling bonus, capped at [MAX_ITEM_SKIP].
 */
fun verticalSteps(distancePx: Float, velocityPxPerS: Float, stepPx: Float, flingPx: Float): Int {
    val commit = stepPx * 0.6f
    if (abs(distancePx) < commit && abs(velocityPxPerS) < flingPx) return 0
    val base = (abs(distancePx) / stepPx).roundToInt().coerceAtLeast(1)
    val bonus = when {
        abs(velocityPxPerS) > flingPx * 3f -> 2
        abs(velocityPxPerS) > flingPx      -> 1
        else                               -> 0
    }
    val magnitude = (base + bonus).coerceIn(1, MAX_ITEM_SKIP)
    return if (distancePx < 0) magnitude else -magnitude
}
