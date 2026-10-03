package com.playfieldportal.feature.xmb.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The one spring behind every XMB selection step: the category bar's slide and the item lists'
 * glide. They share it so the two axes cannot drift apart; change the feel here, not at a call site.
 *
 * Deliberately NOT used by the row scale/alpha springs or the bar's icon and label springs, which
 * are a different motion.
 */
object XmbStepSpring {
    const val STIFFNESS = Spring.StiffnessMediumLow
    const val DAMPING = Spring.DampingRatioNoBouncy

    /** [visibilityThreshold] is the settle distance; null keeps the type's default. */
    fun <T> spec(visibilityThreshold: T? = null): SpringSpec<T> =
        spring(dampingRatio = DAMPING, stiffness = STIFFNESS, visibilityThreshold = visibilityThreshold)
}

/** A jump of more than this many rows snaps to one row short of the target, then glides the last row. */
const val LONG_JUMP_ROWS = 3

/**
 * Top of item [index] in px while the list's animated position is [position] (the selected index in
 * rows). Every anchor is pre-rounded to the integer today's layout produces, so at rest the result is
 * exactly today's: below rows at `belowTopPx + k * rowPx`, the previous row at the centre of its
 * half-row window (`requiredHeight` overflow is centred, so it shows the row's middle half).
 *
 * With `d = index - position`: `d >= 0` is the below column, `-1 <= d < 0` slides linearly between the
 * below slot and the previous slot, and `d < -1` keeps rising at [rowPx] per row. Only rows after the
 * UMD row ([umdIndex], -1 for none) carry [umdExtraPx]: in the below column, where the `Column`
 * pushed them down today, and into the start of the slide above it so there is no jump at `d = 0`.
 * It fades out by `d = -1`, where the extra is long gone at rest.
 */
fun itemRowTopPx(
    index: Int,
    position: Float,
    belowTopPx: Int,
    winTopPx: Int,
    winPx: Int,
    rowPx: Int,
    umdIndex: Int,
    umdExtraPx: Int,
): Float {
    val d = index - position
    val prevTopPx = winTopPx + (winPx - rowPx) / 2
    val extraPx = if (index > umdIndex) umdExtraPx else 0
    return when {
        d >= 0f -> belowTopPx + d * rowPx + extraPx
        // Starts from the below slot INCLUDING the extra, so a row after the UMD row is continuous
        // across d = 0 while the UMD row is still shrinking back.
        d >= -1f -> prevTopPx + (belowTopPx + extraPx - prevTopPx) * (d + 1f)
        else -> prevTopPx + (d + 1f) * rowPx
    }
}

/**
 * The item indices to compose. At rest ([position] equals the target) this is exactly today's set:
 * the row above only when the raw index is in `1..lastIndex`, then [rowsBelow] whole rows from the
 * selection. In transit it follows [position], not the target, so it also covers the rows a lagging
 * spring still shows: `floor(p) - 1 .. ceil(p) + rowsBelow - 1`, clamped to the list.
 */
fun itemRowWindow(rawSelectedIndex: Int, itemCount: Int, rowsBelow: Int, position: Float): IntRange {
    if (itemCount <= 0) return IntRange.EMPTY
    val last = itemCount - 1
    val sel = rawSelectedIndex.coerceIn(0, last)
    if (position == sel.toFloat()) {
        val first = if (rawSelectedIndex in 1..last) sel - 1 else sel
        return first..(minOf(itemCount, sel + rowsBelow) - 1)
    }
    val first = (floor(position).toInt() - 1).coerceAtLeast(0)
    val end = (ceil(position).toInt() + rowsBelow - 1).coerceAtMost(last)
    return first..end
}

/** Which part of a row is drawn; see [itemRowClip]. */
enum class ItemRowClip { None, Window, BelowOrWindow, BottomLimit }

/**
 * How to clip a row at `d = index - position`. Rows at or below the selected slot stay unclipped so a
 * lifted row's move outline and badge, which reach above it, are not shaved. A row at or above the
 * previous slot gets the half-row window; one between the two slots gets the union of "below the
 * selected slot" and that window. In transit ([atRest] false) a row reaching past the last whole slot
 * is cut at the line under it, so no partial row shows and none pops out when the list settles.
 */
fun itemRowClip(d: Float, rowsBelow: Int, atRest: Boolean): ItemRowClip = when {
    d <= -1f -> ItemRowClip.Window
    d < 0f -> ItemRowClip.BelowOrWindow
    !atRest && d > rowsBelow - 1 -> ItemRowClip.BottomLimit
    else -> ItemRowClip.None
}

/** What the list's step rules compare from one set of inputs to the next. */
data class StepInputs(
    val rawSelectedIndex: Int,
    val itemCount: Int,
    val scrollToTopToken: Int,
    val columnKey: Any?,
    val moving: Boolean,
    // Bumps when the cursor lands on a section's default row (XMBUiState.landingToken): a new
    // section is never scrolled to, only shown.
    val landingToken: Int = 0,
) {
    val target: Int get() = rawSelectedIndex.coerceIn(0, maxOf(itemCount - 1, 0))
}

/** How the animated position reaches [target]. */
sealed interface StepMotion {
    val target: Int

    data class Snap(override val target: Int) : StepMotion
    data class Glide(override val target: Int) : StepMotion

    /** Snap to [from] (one row short of [target]), then glide the last row. */
    data class SnapThenGlide(val from: Int, override val target: Int) : StepMotion
}

/**
 * Decides how the list moves between two sets of inputs. Snaps for: no previous inputs, either raw
 * index negative (the `AnimatedContent` outgoing copy and re-entry), either list empty, a changed
 * scroll-to-top token, column key or landing token, and a Move on either side. Otherwise a jump of more than
 * [LONG_JUMP_ROWS] snaps to one row short and glides the last row; anything else glides.
 *
 * The jump is measured between targets, never from the animated value, so a held repeat (always one
 * row) stays on the spring even while the spring trails it.
 */
fun xmbStepMotion(prev: StepInputs?, next: StepInputs): StepMotion {
    val target = next.target
    if (prev == null ||
        prev.rawSelectedIndex < 0 || next.rawSelectedIndex < 0 ||
        prev.itemCount == 0 || next.itemCount == 0 ||
        prev.scrollToTopToken != next.scrollToTopToken ||
        prev.columnKey != next.columnKey ||
        prev.landingToken != next.landingToken ||
        prev.moving || next.moving
    ) return StepMotion.Snap(target)
    val delta = target - prev.target
    return when {
        delta > LONG_JUMP_ROWS -> StepMotion.SnapThenGlide(from = target - 1, target = target)
        delta < -LONG_JUMP_ROWS -> StepMotion.SnapThenGlide(from = target + 1, target = target)
        else -> StepMotion.Glide(target)
    }
}


// ── Rewind: the hand-off across the catbar (Display ▸ Item List Motion) ─────────

/**
 * Timings of Rewind's hand-off, in ms. ▼ sends the focused row up behind the category icon over
 * [CROSS_MS] while the column waits [COLUMN_LAG_MS]; ▲ plays it backwards — the column moves at
 * once and the row above the bar waits [DROP_DELAY_MS] before dropping in over [CROSS_MS]. A step
 * within [HOLD_GAP_MS] of the last one is a held repeat and glides as one column.
 */
object XmbHandOff {
    const val CROSS_MS = 80L
    const val COLUMN_LAG_MS = 30L
    const val DROP_DELAY_MS = 40L
    const val HOLD_GAP_MS = 200L
}

/**
 * The XMB item list's glide (Glide, and Rewind's column): one constant speed, [MS_PER_ROW] per row,
 * instead of the category bar's spring, whose slow settle read as uneven. A held run that has
 * fallen behind takes at most [MAX_MS] to catch up, so the list never trails the thumb.
 */
object XmbGlide {
    const val MS_PER_ROW = 100L
    const val MAX_MS = 200L
}

/** How long the column takes from [from] to [to] (positions in rows) at the glide's constant speed. */
fun glideDurationMs(from: Float, to: Float): Int =
    (kotlin.math.abs(to - from) * XmbGlide.MS_PER_ROW).toLong().coerceAtMost(XmbGlide.MAX_MS).toInt()

/**
 * The one row crossing the category bar in a hand-off: [row] travels from [fromD] to [toD] (its
 * `index - position`, as in [itemRowTopPx]) starting at [startMs]. [down] is a ▼ step: the
 * focused row rising to the previous slot; otherwise the row above dropping into focus.
 */
data class Crossing(val row: Int, val down: Boolean, val fromD: Float, val toD: Float, val startMs: Long)

/**
 * The hand-off for a step from [fromIndex] to [toIndex] at [nowMs], or null when the step glides
 * instead: anything but a single row, or a held repeat (the last step was under
 * [XmbHandOff.HOLD_GAP_MS] ago). [fromD] is where the crossing row is drawn right now.
 */
fun handOffFor(fromIndex: Int, toIndex: Int, nowMs: Long, lastStepMs: Long?, fromD: Float): Crossing? {
    if (toIndex - fromIndex != 1 && fromIndex - toIndex != 1) return null
    if (lastStepMs != null && nowMs - lastStepMs < XmbHandOff.HOLD_GAP_MS) return null
    return if (toIndex > fromIndex) {
        Crossing(row = fromIndex, down = true, fromD = fromD, toD = -1f, startMs = nowMs)
    } else {
        Crossing(row = toIndex, down = false, fromD = fromD, toD = 0f, startMs = nowMs + XmbHandOff.DROP_DELAY_MS)
    }
}

/** When the column starts toward the new selection: after the lag on a ▼ hand-off, else now. */
fun columnStartMs(step: Crossing?, nowMs: Long): Long =
    if (step?.down == true) nowMs + XmbHandOff.COLUMN_LAG_MS else nowMs

/**
 * When the new selection may draw as focused. Until then no row is: the leaving row dims at once,
 * and the new one grows as the column (▼) or the dropping row (▲) sets off.
 */
fun focusStartMs(step: Crossing?, nowMs: Long): Long = when {
    step == null -> nowMs
    step.down -> nowMs + XmbHandOff.COLUMN_LAG_MS
    else -> step.startMs
}

private fun crossingProgress(c: Crossing, nowMs: Long): Float =
    ((nowMs - c.startMs).toFloat() / XmbHandOff.CROSS_MS).coerceIn(0f, 1f)

/**
 * The crossing row's `d` at [nowMs], given where the column would put it ([columnD]). A rising
 * row never lags the column (a later step carries it on up); a dropping row holds above the bar
 * until its drop, then never trails the column either.
 */
fun crossingD(c: Crossing, nowMs: Long, columnD: Float): Float {
    val k = crossingProgress(c, nowMs)
    // Linear, like the glide: one constant speed through the whole crossing.
    val fast = c.fromD + (c.toD - c.fromD) * k
    if (!c.down && k < 1f) return fast
    return if (c.down) minOf(fast, columnD) else maxOf(fast, columnD)
}

/** True once the crossing has landed and the column has caught up with it: it is a plain row again. */
fun crossingDone(c: Crossing, nowMs: Long, columnD: Float): Boolean =
    crossingProgress(c, nowMs) >= 1f && if (c.down) columnD <= c.toD + 1e-3f else columnD >= c.toD - 1e-3f

/**
 * How much of a row at [d] draws in the catbar band — the strip between the half-row window and
 * the focus slot, which the clip used to hide. Any row in transit between the two slots (a
 * hand-off, a glide, a held run) draws there in full, behind the category icon, which draws on
 * top; it fades in as it leaves the focus slot and out over the last fifth into the previous slot,
 * so at rest the half split is exactly as before.
 */
fun transitBandAlpha(d: Float): Float {
    if (d >= 0f || d <= -1f) return 0f
    return minOf(1f, -d / 0.08f, (d + 1f) / 0.2f)
}
