package com.playfieldportal.feature.xmb.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.ControllerPrompt
import com.playfieldportal.core.ui.components.XmbHeaderPill
import com.playfieldportal.core.ui.preview.CombinedPreviews
import com.playfieldportal.core.ui.preview.PfpPreview
import com.playfieldportal.core.ui.theme.LocalPFPColors

// The Studio's paging and options controls, stateless so they can be previewed: the screen itself
// takes a Hilt ViewModel. Touch mode draws each as a pill a user can see is tappable; controller
// mode keeps the compact hints whose glyphs follow the live bindings.

/**
 * The page line under the grid: range on the left, paging on the right.
 *
 * The band keeps its height even with nothing to count, so a line that appeared only once results
 * arrived cannot shrink the grid slot mid-load and re-page what was just measured. It is 40 dp in
 * touch mode, so the pills are never clipped, and 16 dp otherwise. That one height change
 * re-measures the grid when the mode flips; AD-17 keeps the focused result focused across it.
 */
@Composable
internal fun StudioPageLine(
    rangeStart: Int,
    rangeEnd: Int,
    totalResults: Int,
    page: Int,
    pageCount: Int,
    hasPreviousPage: Boolean,
    hasNextPage: Boolean,
    showTouchControls: Boolean,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    modifier: Modifier = Modifier,
    // The active tab's changes and their downloads (tasks 5.1, 5.2), after the range and inside the band's height.
    picks: StudioQueueSummary = StudioQueueSummary(),
    onApply: () -> Unit = {},
    onRetryFailed: () -> Unit = {},
    onRemoveFailed: () -> Unit = {},
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().height(if (showTouchControls) 40.dp else 16.dp),
    ) {
        // Texts here set lineHeight: the theme's 24 sp bodyLarge line is taller than the 16 dp band,
        // which drew the text ~3 dp below the centred LB/RB glyphs.
        if (totalResults > 0) {
            Text(
                "$rangeStart–$rangeEnd of $totalResults",
                color = Color.White.copy(alpha = 0.6f), fontSize = 9.5.sp, lineHeight = 12.sp,
                maxLines = 1,
            )
        }
        // Counted even with no results: a pick made on another source or query is still held.
        if (picks.hasChanges || picks.inQueue) {
            StudioPickStatus(
                picks = picks,
                showTouchControls = showTouchControls,
                onApply = onApply,
                onRetryFailed = onRetryFailed,
                onRemoveFailed = onRemoveFailed,
                modifier = Modifier.padding(start = if (totalResults > 0) 10.dp else 0.dp),
            )
        }
        Spacer(Modifier.weight(1f))
        // A single page has nothing to move to, so only the range shows.
        if (pageCount > 1 && showTouchControls) {
            TouchPagePill("‹ Prev", enabled = hasPreviousPage, onClick = onPreviousPage)
            Text(
                "Page ${page + 1} / $pageCount",
                color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
            TouchPagePill("Next ›", enabled = hasNextPage, onClick = onNextPage)
        } else if (pageCount > 1) {
            // The arrows stay tappable; LB/RB page while the grid is focused.
            ControllerPrompt(
                action = GamepadAction.PREV_CATEGORY,
                label = "",
                glyphSize = 12.dp,
                labelColor = Color.White.copy(alpha = 0.45f),
            )
            PageArrow("‹", enabled = hasPreviousPage, onClick = onPreviousPage)
            Text(
                "Page ${page + 1} / $pageCount",
                color = Color.White.copy(alpha = 0.6f), fontSize = 9.5.sp, lineHeight = 12.sp,
                maxLines = 1,
            )
            PageArrow("›", enabled = hasNextPage, onClick = onNextPage)
            ControllerPrompt(
                action = GamepadAction.NEXT_CATEGORY,
                label = "",
                glyphSize = 12.dp,
                labelColor = Color.White.copy(alpha = 0.45f),
            )
        }
    }
}

/**
 * The page line's changes: what waits for Apply ("2 to add · 1 to remove", or "+2 −1" in touch mode
 * where the pills need the width), then how the queue is doing ("2 of 5 added · 1 failed"). Touch draws
 * Apply, Retry and Remove as pills; a controller applies with START and retries or removes failures
 * from the options menu, so it only needs the hint and the text.
 */
@Composable
private fun StudioPickStatus(
    picks: StudioQueueSummary,
    showTouchControls: Boolean,
    onApply: () -> Unit,
    onRetryFailed: () -> Unit,
    onRemoveFailed: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier,
    ) {
        if (picks.hasChanges) {
            val changes = if (showTouchControls) {
                listOfNotNull("+${picks.toAdd}".takeIf { picks.toAdd > 0 }, "−${picks.toRemove}".takeIf { picks.toRemove > 0 })
                    .joinToString(" ")
            } else {
                listOfNotNull(
                    "${picks.toAdd} to add".takeIf { picks.toAdd > 0 },
                    "${picks.toRemove} to remove".takeIf { picks.toRemove > 0 },
                ).joinToString(" · ")
            }
            Text(
                changes,
                color = Color.White, fontSize = 9.5.sp, lineHeight = 12.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            if (showTouchControls) {
                XmbHeaderPill(label = "Apply ›", onClick = onApply)
            } else {
                ControllerPrompt(
                    action = GamepadAction.HOME,
                    label = "Apply",
                    glyphSize = 12.dp,
                    labelColor = Color.White.copy(alpha = 0.6f),
                    labelStyle = TextStyle(fontSize = 9.5.sp, lineHeight = 12.sp),
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onApply),
                )
            }
        }
        if (picks.inQueue) {
            Text(
                buildString {
                    append("${picks.added} of ${picks.total} added")
                    if (picks.failed > 0) append(" · ${picks.failed} failed")
                },
                color = if (picks.failed > 0) Color(0xFFE0A030) else Color.White.copy(alpha = 0.6f),
                fontSize = 9.5.sp, lineHeight = 12.sp,
                maxLines = 1,
            )
            if (picks.failed > 0 && showTouchControls) {
                XmbHeaderPill(label = "Retry", onClick = onRetryFailed)
                XmbHeaderPill(label = "Remove", onClick = onRemoveFailed)
            }
        }
    }
}

/**
 * The rail's options control. Controller mode shows the Y hint; in touch mode a small hint does not
 * read as tappable, so it becomes a pill. Either way [onClick] opens the actions menu, which decides
 * for itself whether anything can open.
 */
@Composable
internal fun StudioOptionsControl(
    showTouchControls: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (showTouchControls) {
        XmbHeaderPill(label = "Options", onClick = onClick, modifier = modifier, leadingGlyph = "⋯")
    } else {
        ControllerPrompt(
            action = GamepadAction.OPEN_CONTEXT_MENU,
            label = "Crop, restore, clear",
            glyphSize = 13.dp,
            labelColor = Color.White.copy(alpha = 0.6f),
            labelStyle = TextStyle(fontSize = 9.5.sp),
            modifier = modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onClick)
                .padding(vertical = 4.dp),
        )
    }
}

/** Prev / page / Next under a manual (PDF) candidate preview. [page] is 0-based. */
@Composable
internal fun StudioManualPager(
    page: Int,
    pageCount: Int,
    showTouchControls: Boolean,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasPrevious = page > 0
    val hasNext = page < pageCount - 1
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        ManualPagerButton("‹ Prev", hasPrevious, showTouchControls, onPreviousPage)
        Text(
            "Page ${page + 1} / ${pageCount.coerceAtLeast(1)}",
            color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        ManualPagerButton("Next ›", hasNext, showTouchControls, onNextPage)
    }
}

// Dimmed and inert at either end, rather than hidden, so the pager keeps its shape.
@Composable
private fun TouchPagePill(label: String, enabled: Boolean, onClick: () -> Unit) {
    XmbHeaderPill(
        label = label,
        onClick = { if (enabled) onClick() },
        modifier = Modifier.alpha(if (enabled) 1f else 0.4f),
    )
}

@Composable
private fun PageArrow(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        glyph,
        color = Color.White.copy(alpha = if (enabled) 0.85f else 0.3f),
        fontSize = 11.sp, fontWeight = FontWeight.SemiBold, lineHeight = 12.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp),
    )
}

@Composable
private fun ManualPagerButton(label: String, enabled: Boolean, showTouchControls: Boolean, onClick: () -> Unit) {
    if (showTouchControls) {
        TouchPagePill(label, enabled, onClick)
    } else {
        Text(
            label, color = Color.White.copy(alpha = if (enabled) 0.85f else 0.3f),
            fontSize = 12.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────
// Each shows touch mode above controller mode. Widths are the AYN Thor's measured grid slot
// (613 dp) and rail (150 dp), so what fits here fits there.

@Composable
private fun StudioPreviewBackdrop(content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalPFPColors.current
    Column(
        modifier = Modifier
            .background(Brush.verticalGradient(0f to colors.backgroundTop, 1f to colors.backgroundBottom))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun PreviewCaption(text: String) {
    Text(text, color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp)
}

@CombinedPreviews
@Composable
private fun StudioPageLinePreview() {
    PfpPreview {
        StudioPreviewBackdrop {
            for (touch in listOf(true, false)) {
                val mode = if (touch) "Touch" else "Controller"
                PreviewCaption("$mode · first page")
                StudioPageLine(
                    rangeStart = 1, rangeEnd = 15, totalResults = 50, page = 0, pageCount = 4,
                    hasPreviousPage = false, hasNextPage = true, showTouchControls = touch,
                    onPreviousPage = {}, onNextPage = {}, modifier = Modifier.width(613.dp),
                )
                PreviewCaption("$mode · middle page · 3 to add")
                StudioPageLine(
                    rangeStart = 16, rangeEnd = 30, totalResults = 50, page = 1, pageCount = 4,
                    hasPreviousPage = true, hasNextPage = true, showTouchControls = touch,
                    onPreviousPage = {}, onNextPage = {}, modifier = Modifier.width(613.dp),
                    picks = StudioQueueSummary(toAdd = 3),
                )
                // The widest the line gets: changes waiting, a failure and the pager, at the Thor's slot.
                PreviewCaption("$mode · middle page · 2 to add, 1 to remove · 3 of 4 added, 1 failed")
                StudioPageLine(
                    rangeStart = 16, rangeEnd = 30, totalResults = 50, page = 1, pageCount = 4,
                    hasPreviousPage = true, hasNextPage = true, showTouchControls = touch,
                    onPreviousPage = {}, onNextPage = {}, modifier = Modifier.width(613.dp),
                    picks = StudioQueueSummary(toAdd = 2, toRemove = 1, added = 3, failed = 1, total = 4),
                )
                PreviewCaption("$mode · single page (no pager)")
                StudioPageLine(
                    rangeStart = 1, rangeEnd = 6, totalResults = 6, page = 0, pageCount = 1,
                    hasPreviousPage = false, hasNextPage = false, showTouchControls = touch,
                    onPreviousPage = {}, onNextPage = {}, modifier = Modifier.width(613.dp),
                )
            }
        }
    }
}

@CombinedPreviews
@Composable
private fun StudioOptionsControlPreview() {
    PfpPreview {
        StudioPreviewBackdrop {
            PreviewCaption("Touch")
            Column(Modifier.width(150.dp)) { StudioOptionsControl(showTouchControls = true, onClick = {}) }
            PreviewCaption("Controller")
            Column(Modifier.width(150.dp)) { StudioOptionsControl(showTouchControls = false, onClick = {}) }
        }
    }
}

@CombinedPreviews
@Composable
private fun StudioManualPagerPreview() {
    PfpPreview {
        StudioPreviewBackdrop {
            for (touch in listOf(true, false)) {
                PreviewCaption(if (touch) "Touch · page 1 of 12" else "Controller · page 1 of 12")
                StudioManualPager(page = 0, pageCount = 12, showTouchControls = touch, onPreviousPage = {}, onNextPage = {})
            }
        }
    }
}
