package com.playfieldportal.feature.xmb.ui

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState

/**
 * Scrolls row [target] on screen for a cursor that has just moved there, travelling the shortest
 * distance that does it.
 *
 * The pitfall this exists for: [LazyListState.scrollToItem] aligns the row to the **top** of the
 * viewport. That is exactly one row of travel when the cursor steps *up* past the first visible row
 * — the row above becomes the new top row — but a whole page when it steps *down* past the last
 * one, because the row that was off the bottom is thrown to the top and every row in between moves
 * with it. Held down, the cursor walks to the bottom edge, lurches a page, and walks again, where a
 * hold up glides one row at a time; the two directions should read the same.
 *
 * So a downward one-row step is composed top-aligned and then scrolled back until the row sits
 * flush against the bottom edge — the same single row of travel, and the reveal is seated where the
 * row is going rather than where it came from. The distance needs the row's own height, which is not
 * known until the row has been laid out once, so it is read after that first scroll; both happen in
 * the same frame, so nothing between them is drawn.
 *
 * A row above the window keeps the plain top-align (already minimal), and a move of more than one
 * row — a sort cycling back to the top, a query refiltering the list — stays a jump rather than
 * being seated at the bottom edge.
 */
internal suspend fun LazyListState.revealRow(target: Int) {
    val window = layoutInfo
    val lastVisible = window.visibleItemsInfo.lastOrNull()?.index
    scrollToItem(target)
    val placed = layoutInfo.visibleItemsInfo.firstOrNull { it.index == target } ?: return
    val back = scrollBackForReveal(
        target = target,
        lastVisibleIndex = lastVisible,
        viewportLength = window.viewportEndOffset - window.viewportStartOffset,
        rowHeight = placed.size,
    )
    if (back > 0) scrollBy(-back.toFloat())
}

/**
 * How far to scroll back once [LazyListState.scrollToItem] has composed the row top-aligned: the
 * viewport less the row's own height for a downward one-row step, nothing for anything else.
 *
 * Pure so the rule — the thing that makes a held direction read the same in both directions — is
 * pinned by a plain test instead of by a device.
 */
internal fun scrollBackForReveal(
    target: Int,
    lastVisibleIndex: Int?,
    viewportLength: Int,
    rowHeight: Int,
): Int =
    if (lastVisibleIndex != null && target == lastVisibleIndex + 1) {
        // A row taller than the window cannot be seated flush; leave it top-aligned and fully
        // visible from the top down, which is the most it can be.
        (viewportLength - rowHeight).coerceAtLeast(0)
    } else {
        0
    }
