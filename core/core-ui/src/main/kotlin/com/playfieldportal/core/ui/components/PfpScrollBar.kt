package com.playfieldportal.core.ui.components

import android.provider.Settings
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.ui.theme.LocalPfpTextColors
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

// ── Slim scroll bar ──────────────────────────────────────────────────────────
//
// A 4dp track on the end edge with a thumb, and a small solid arrow beyond each end of the track
// that shows only while there is more content that way. Display-only for a controller: nothing in
// here is focusable, so it can never take the cursor. Touch can drag the thumb or tap an arrow.
//
// Everything that is arithmetic lives in the pure functions below so it can be unit-tested.

/** Width of the visible track and thumb. */
private val TrackWidth = 4.dp
/** Gap between the bar and the screen edge. */
private val EdgeInset = 4.dp
/** The bar's own width: the touch target for the thumb and arrows. */
private val BarWidth = 48.dp
/** Height reserved at each end of the track for its arrow (also the arrow's tap target). */
private val ArrowSlot = 24.dp
private val ArrowWidth = 10.dp
private val ArrowHeight = 7.dp
private val MinThumb = 24.dp
private val MinThumbTouchHeight = 48.dp

private const val TrackAlpha = 0.25f
private const val ThumbAlpha = 0.85f

/** What a scroller reports: position, furthest position, and the visible window, all in pixels. */
internal data class ScrollBarMetrics(val value: Float, val maxValue: Float, val viewport: Float)

/** Whether there is anything to scroll; false means the bar is hidden entirely. */
internal fun scrollBarShown(maxValue: Float): Boolean = maxValue > 0f

/** More content before the current position (above, in a vertical list). */
internal fun canScrollBack(value: Float): Boolean = value > 0f

/** More content after the current position (below, in a vertical list). */
internal fun canScrollForward(value: Float, maxValue: Float): Boolean = maxValue > 0f && value < maxValue

/**
 * The thumb's length: the track scaled by the share of the content that is visible, never below
 * [minThumb] (capped to the track). Zero when the content fits or there is no track.
 */
internal fun scrollThumbLength(track: Float, viewport: Float, maxValue: Float, minThumb: Float): Float {
    if (track <= 0f || !scrollBarShown(maxValue)) return 0f
    val content = viewport + maxValue
    val raw = if (content <= 0f) track else track * viewport / content
    return raw.coerceAtLeast(minThumb).coerceAtMost(track)
}

/** The thumb's distance from the start of the track for scroll [value] (clamped to 0..[maxValue]). */
internal fun scrollThumbOffset(track: Float, thumb: Float, value: Float, maxValue: Float): Float {
    if (maxValue <= 0f) return 0f
    return (track - thumb).coerceAtLeast(0f) * (value / maxValue).coerceIn(0f, 1f)
}

/** How far the content scrolls for a thumb drag of [dragPx]; zero when the thumb fills the track. */
internal fun thumbDragToScroll(dragPx: Float, track: Float, thumb: Float, maxValue: Float): Float {
    val free = track - thumb
    return if (free <= 0f || maxValue <= 0f) 0f else dragPx * maxValue / free
}

/**
 * Metrics for a lazy scroller, which knows only what it has laid out: the average visible item
 * stands in for the rest. [firstIndex], [totalItems] and [visibleItems] are in the scroller's own
 * units (items for a list, lines for a grid); the extents are pixels.
 */
internal fun estimateLazyMetrics(
    firstIndex: Int,
    firstOffset: Int,
    totalItems: Int,
    visibleItems: Int,
    visibleExtent: Int,
    viewport: Int,
): ScrollBarMetrics {
    if (totalItems <= 0 || visibleItems <= 0 || visibleExtent <= 0) {
        return ScrollBarMetrics(0f, 0f, viewport.toFloat())
    }
    val average = visibleExtent.toFloat() / visibleItems
    val content = average * totalItems
    val maxValue = (content - viewport).coerceAtLeast(0f)
    val value = (firstIndex * average + firstOffset).coerceIn(0f, maxValue)
    return ScrollBarMetrics(value, maxValue, viewport.toFloat())
}

// ── Public overloads ─────────────────────────────────────────────────────────

/** The scroll bar for a `verticalScroll` body. Align it to the end edge of the body's box. */
@Composable
fun PfpScrollBar(state: ScrollState, modifier: Modifier = Modifier) {
    PfpScrollBarLayer(
        scrollable = state,
        metrics = {
            ScrollBarMetrics(state.value.toFloat(), state.maxValue.toFloat(), state.viewportSize())
        },
        modifier = modifier,
    )
}

/** The scroll bar for a vertical [androidx.compose.foundation.lazy.LazyColumn]. */
@Composable
fun PfpScrollBar(state: LazyListState, modifier: Modifier = Modifier) {
    PfpScrollBarLayer(
        scrollable = state,
        metrics = {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            estimateLazyMetrics(
                firstIndex = state.firstVisibleItemIndex,
                firstOffset = state.firstVisibleItemScrollOffset,
                totalItems = info.totalItemsCount,
                visibleItems = visible.size,
                visibleExtent = visible.sumOf { it.size },
                viewport = info.viewportEndOffset - info.viewportStartOffset,
            )
        },
        modifier = modifier,
    )
}

/** The scroll bar for a vertical [androidx.compose.foundation.lazy.grid.LazyVerticalGrid]. */
@Composable
fun PfpScrollBar(state: LazyGridState, modifier: Modifier = Modifier) {
    PfpScrollBarLayer(
        scrollable = state,
        metrics = {
            val info = state.layoutInfo
            val visible = info.visibleItemsInfo
            val lines = visible.groupBy { it.offset.y }
            val perLine = if (lines.isEmpty()) 1 else ceil(visible.size / lines.size.toDouble()).toInt().coerceAtLeast(1)
            estimateLazyMetrics(
                firstIndex = state.firstVisibleItemIndex / perLine,
                firstOffset = state.firstVisibleItemScrollOffset,
                totalItems = ceil(info.totalItemsCount / perLine.toDouble()).toInt(),
                visibleItems = lines.size,
                visibleExtent = lines.values.sumOf { line -> line.maxOf { it.size.height } },
                viewport = info.viewportEndOffset - info.viewportStartOffset,
            )
        },
        modifier = modifier,
    )
}

// ScrollState does not publish its viewport size, so it reports 0 here and the layer substitutes
// its own measured height (the bar is laid out to the body's full height).
private fun ScrollState.viewportSize(): Float = 0f

// ── The layer ────────────────────────────────────────────────────────────────

@Composable
private fun rememberStepsInstantly(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
}

@Composable
private fun PfpScrollBarLayer(
    scrollable: ScrollableState,
    metrics: () -> ScrollBarMetrics,
    modifier: Modifier,
) {
    var heightPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val instant = rememberStepsInstantly()
    val scope = rememberCoroutineScope()

    val raw = metrics()
    // A ScrollState cannot report its viewport; the body's own box is the viewport, which is this
    // layer's height (the bar is laid out to the body's full height).
    val m = if (raw.viewport > 0f) raw else raw.copy(viewport = heightPx.toFloat())
    if (!scrollBarShown(m.maxValue) || heightPx == 0 && raw.viewport <= 0f) {
        // Still measure while hidden, or a ScrollState-backed bar could never discover its height.
        Box(modifier.fillMaxHeight().width(BarWidth).onSizeChanged { heightPx = it.height })
        return
    }

    val colors = LocalPfpTextColors.current
    val barColor = colors.primary
    val slotPx = with(density) { ArrowSlot.toPx() }
    val trackPx = (heightPx - 2 * slotPx).coerceAtLeast(0f)
    val thumbPx = scrollThumbLength(trackPx, m.viewport, m.maxValue, with(density) { MinThumb.toPx() })
    val thumbTop = scrollThumbOffset(trackPx, thumbPx, m.value, m.maxValue)
    val back = canScrollBack(m.value)
    val forward = canScrollForward(m.value, m.maxValue)

    val latestMetrics by rememberUpdatedState(m)
    val latestTrack by rememberUpdatedState(trackPx)
    val latestThumb by rememberUpdatedState(thumbPx)

    fun step(direction: Float) {
        scope.launch {
            val delta = direction * latestMetrics.viewport
            if (instant) scrollable.scrollBy(delta) else scrollable.animateScrollBy(delta)
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(BarWidth)
            // Display only for a controller: pointer handling below never makes this focusable, and
            // the explicit property keeps it out of any focus search that passes through.
            .focusProperties { canFocus = false }
            .onSizeChanged { heightPx = it.height },
    ) {
        Canvas(Modifier.fillMaxHeight().width(BarWidth)) {
            val rtl = layoutDirection == LayoutDirection.Rtl
            val trackW = TrackWidth.toPx()
            val centerX = if (rtl) EdgeInset.toPx() + trackW / 2f else size.width - EdgeInset.toPx() - trackW / 2f
            val left = centerX - trackW / 2f
            val twin = ShadowTwinOffset.toPx()

            // Track, then thumb, each with its matte twin so they read over any wallpaper.
            drawBar(left + twin, slotPx + twin, trackW, trackPx, shadowTwinColor(barColor.copy(alpha = TrackAlpha)))
            drawBar(left, slotPx, trackW, trackPx, barColor.copy(alpha = TrackAlpha))
            drawBar(left + twin, slotPx + thumbTop + twin, trackW, thumbPx, shadowTwinColor(barColor.copy(alpha = ThumbAlpha)))
            drawBar(left, slotPx + thumbTop, trackW, thumbPx, barColor.copy(alpha = ThumbAlpha))

            val aw = ArrowWidth.toPx()
            val ah = ArrowHeight.toPx()
            if (back) {
                val cy = slotPx / 2f
                drawArrow(centerX + twin, cy + twin, aw, ah, up = true, color = shadowTwinColor(barColor))
                drawArrow(centerX, cy, aw, ah, up = true, color = barColor)
            }
            if (forward) {
                val cy = size.height - slotPx / 2f
                drawArrow(centerX + twin, cy + twin, aw, ah, up = false, color = shadowTwinColor(barColor))
                drawArrow(centerX, cy, aw, ah, up = false, color = barColor)
            }
        }

        // Thumb: a 48dp-wide, at-least-48dp-tall drag target centred on the visible thumb.
        val minTouchPx = with(density) { MinThumbTouchHeight.toPx() }
        val touchPx = max(thumbPx, minTouchPx)
        val touchTop = (slotPx + thumbTop + thumbPx / 2f - touchPx / 2f)
            .coerceIn(0f, (heightPx - touchPx).coerceAtLeast(0f))
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, touchTop.roundToInt()) }
                .width(BarWidth)
                .height(with(density) { touchPx.toDp() })
                .pointerInput(scrollable) {
                    detectVerticalDragGestures { change, dragAmount ->
                        change.consume()
                        scrollable.dispatchRawDelta(
                            thumbDragToScroll(dragAmount, latestTrack, latestThumb, latestMetrics.maxValue),
                        )
                    }
                },
        )

        // Arrows: tappable only while drawn.
        if (back) {
            Box(
                Modifier.align(Alignment.TopEnd).width(BarWidth).height(ArrowSlot)
                    .pointerInput(scrollable) { detectTapGestures { step(-1f) } },
            )
        }
        if (forward) {
            Box(
                Modifier.align(Alignment.BottomEnd).width(BarWidth).height(ArrowSlot)
                    .pointerInput(scrollable) { detectTapGestures { step(1f) } },
            )
        }
    }
}

private fun DrawScope.drawBar(left: Float, top: Float, width: Float, length: Float, color: Color) {
    if (length <= 0f || color.alpha == 0f) return
    drawRoundRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size(width, length),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(width / 2f, width / 2f),
    )
}

/** A small solid triangle centred on ([cx], [cy]): pointing up when [up], down otherwise. */
private fun DrawScope.drawArrow(cx: Float, cy: Float, width: Float, height: Float, up: Boolean, color: Color) {
    if (color.alpha == 0f) return
    val tip = if (up) cy - height / 2f else cy + height / 2f
    val base = if (up) cy + height / 2f else cy - height / 2f
    val path = Path().apply {
        moveTo(cx, tip)
        lineTo(cx + width / 2f, base)
        lineTo(cx - width / 2f, base)
        close()
    }
    drawPath(path, color, style = Fill)
}
