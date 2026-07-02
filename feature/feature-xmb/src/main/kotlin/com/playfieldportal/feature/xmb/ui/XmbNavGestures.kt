package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import kotlin.math.abs

// ── Tuning ─────────────────────────────────────────────────────────────────────
// One vertical "step" of finger travel ≈ one item row (a touch under the list's ROW_HEIGHT so the
// cursor tracks slightly ahead of the finger — feels responsive, not laggy).
private val ITEM_STEP_DP = 64.dp
// One horizontal step ≈ one caticon slot (slightly under CategorySlotWidth 124dp for the same
// ahead-of-the-finger feel while scrubbing the bar).
private val CATEGORY_STEP_DP = 100.dp
// Left-edge band where a rightward drag means Back (matches the prior edge-swipe behaviour).
private val EDGE_DP = 32.dp
private val EDGE_COMMIT_DP = 48.dp
// Fling speed (dp/s) that earns bonus item steps on release, so a quick flick travels further than
// the finger did. Deliberately small (max +2) — momentum, not Android free-scroll.
private val FLING_DP_PER_S = 420f

/**
 * The XMB home-screen touch gesture layer: a single axis-locked, single-pointer detector that
 * translates dragging into the SAME discrete navigation the D-pad produces — never a free scroll.
 *
 * Movement is **live**: while the finger drags, every [ITEM_STEP_DP]/[CATEGORY_STEP_DP] of travel
 * crossed immediately emits another step ([onStepItem] / [onStepCategory]), so a long slide ticks
 * smoothly through items/categories under the finger like scrubbing a real XMB, instead of one
 * notch per gesture. A quick vertical flick adds a small bonus (see [flingBonusSteps]) on release.
 *
 * A drag that STARTS in the left-edge band is reserved for Back ([onEdgeBack]) and never steps.
 * Taps never exceed touch-slop and fall through to the rows' own click handlers. Multi-touch is
 * ignored (the XMB is a single cursor). Guarding (overlays, drill-lock) lives in the ViewModel
 * intents this calls.
 */
fun Modifier.xmbNavGestures(
    onStepCategory: (Int) -> Unit,
    onStepItem: (Int) -> Unit,
    onEdgeBack: () -> Unit,
): Modifier = pointerInput(Unit) {
    val slop = viewConfiguration.touchSlop
    val itemStepPx = ITEM_STEP_DP.toPx()
    val categoryStepPx = CATEGORY_STEP_DP.toPx()
    val edgePx = EDGE_DP.toPx()
    val edgeCommitPx = EDGE_COMMIT_DP.toPx()
    val flingPx = FLING_DP_PER_S * density   // dp/s → px/s

    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false)
            val fromEdge = down.position.x <= edgePx
            var axis = Axis.NONE
            var acc = 0f          // unconsumed drag along the locked axis (px)
            var lockX = 0f        // pre-lock accumulation
            var lockY = 0f
            val tracker = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }

            while (true) {
                val event = awaitPointerEvent()
                // Second finger down → abandon (single-cursor model).
                if (event.changes.count { it.pressed } > 1) axis = Axis.CANCELLED
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break                       // pointer up → gesture ends
                if (axis == Axis.CANCELLED) { change.consume(); continue }

                val d = change.positionChange()
                tracker.addPosition(change.uptimeMillis, change.position)

                when (axis) {
                    Axis.NONE -> {
                        lockX += d.x; lockY += d.y
                        if (abs(lockX) > slop || abs(lockY) > slop) {
                            axis = if (abs(lockX) >= abs(lockY)) Axis.HORIZONTAL else Axis.VERTICAL
                            // Carry the pre-lock travel into the step accumulator.
                            acc = if (axis == Axis.HORIZONTAL) lockX else lockY
                            change.consume()
                        }
                    }
                    Axis.HORIZONTAL -> {
                        acc += d.x
                        change.consume()
                        // Edge gestures are Back-only — no live stepping, committed on release.
                        if (!fromEdge) {
                            val whole = consumeWholeSteps(acc, categoryStepPx)
                            if (whole != 0) {
                                // Drag left (negative) → next category (+1) — content follows finger.
                                onStepCategory(-whole)
                                acc -= whole * categoryStepPx
                            }
                        }
                    }
                    Axis.VERTICAL -> {
                        acc += d.y
                        change.consume()
                        val whole = consumeWholeSteps(acc, itemStepPx)
                        if (whole != 0) {
                            // Drag up (negative) → move DOWN the list (+1) — content follows finger.
                            onStepItem(-whole)
                            acc -= whole * itemStepPx
                        }
                    }
                    Axis.CANCELLED -> Unit
                }
            }

            // Release: commit the edge-Back, or grant a small vertical fling bonus.
            when {
                axis == Axis.HORIZONTAL && fromEdge && acc > edgeCommitPx -> onEdgeBack()
                axis == Axis.VERTICAL -> {
                    val vy = tracker.calculateVelocity().y
                    val bonus = flingBonusSteps(vy, flingPx)
                    if (bonus != 0) onStepItem(bonus)
                }
                else -> Unit
            }
        }
    }
}

private enum class Axis { NONE, HORIZONTAL, VERTICAL, CANCELLED }

/**
 * Whole steps contained in [accumulated] travel at [stepPx] per step, truncated toward zero (pure —
 * unit-tested). The caller subtracts the consumed distance and keeps the remainder, which is what
 * makes a long slide tick continuously instead of once per gesture.
 */
fun consumeWholeSteps(accumulated: Float, stepPx: Float): Int =
    (accumulated / stepPx).toInt()

/**
 * Extra steps granted for a fast release fling (pure — unit-tested). Up-flick (negative velocity)
 * returns positive steps (down the list). Capped at ±2 so a flick adds momentum without ever
 * becoming Android free-scroll.
 */
fun flingBonusSteps(velocityPxPerS: Float, flingPx: Float): Int {
    val magnitude = when {
        abs(velocityPxPerS) > flingPx * 3f -> 2
        abs(velocityPxPerS) > flingPx      -> 1
        else                               -> 0
    }
    return if (velocityPxPerS < 0) magnitude else -magnitude
}
