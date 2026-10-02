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
 * scroll-to-top token or column key, and a Move on either side. Otherwise a jump of more than
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
        prev.moving || next.moving
    ) return StepMotion.Snap(target)
    val delta = target - prev.target
    return when {
        delta > LONG_JUMP_ROWS -> StepMotion.SnapThenGlide(from = target - 1, target = target)
        delta < -LONG_JUMP_ROWS -> StepMotion.SnapThenGlide(from = target + 1, target = target)
        else -> StepMotion.Glide(target)
    }
}
