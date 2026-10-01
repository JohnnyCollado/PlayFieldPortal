package com.playfieldportal.core.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.NotificationDetail
import com.playfieldportal.core.domain.model.PfpErrorCode
import com.playfieldportal.core.domain.model.ResultItem
import com.playfieldportal.core.domain.model.ResultOutcome
import com.playfieldportal.core.domain.model.ResultsLabels
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundSink

// ── The notification detail sheets ────────────────────────────────────────────
//
// Confirm on a Notes or Results row in the notification panel opens one of these over it (the
// notification details plan §7). They share the shared modals' scrim, surface and hint bar, but are
// wider (600 / 720dp) and carry the approved severity stripe and error-code chip.
//
// Like the other shared modals they are stateless: [rememberPfpModalHost] owns the scroll, the
// filter and the cursor, and drives them through [PfpDetailSheetNav].

/** The Results sheet's tabs. [DONE] is shown under the work's own label ("Added", "Updated"). */
enum class ResultFilter { ALL, FAILED, SKIPPED, DONE }

/** The move rules of the detail sheets, for [rememberPfpModalHost] and the tests. */
object PfpDetailSheetNav {

    /** Opens where the trouble is: Failed when anything failed, otherwise everything. */
    fun initialFilter(detail: NotificationDetail.Results): ResultFilter =
        if (detail.items.any { it.outcome == ResultOutcome.FAILED }) ResultFilter.FAILED else ResultFilter.ALL

    /** All, then each bucket that has items — an empty tab is never offered. */
    fun filters(detail: NotificationDetail.Results): List<ResultFilter> = buildList {
        add(ResultFilter.ALL)
        if (detail.count(ResultOutcome.FAILED) > 0) add(ResultFilter.FAILED)
        if (detail.count(ResultOutcome.SKIPPED) > 0) add(ResultFilter.SKIPPED)
        if (detail.count(ResultOutcome.DONE) > 0) add(ResultFilter.DONE)
    }

    fun cycleFilter(current: ResultFilter, detail: NotificationDetail.Results, delta: Int): ResultFilter {
        val offered = filters(detail)
        val here = offered.indexOf(current).coerceAtLeast(0)
        return offered[Math.floorMod(here + delta, offered.size)]
    }

    fun visible(detail: NotificationDetail.Results, filter: ResultFilter): List<ResultItem> = when (filter) {
        ResultFilter.ALL -> detail.items
        ResultFilter.FAILED -> detail.items.filter { it.outcome == ResultOutcome.FAILED }
        ResultFilter.SKIPPED -> detail.items.filter { it.outcome == ResultOutcome.SKIPPED }
        ResultFilter.DONE -> detail.items.filter { it.outcome == ResultOutcome.DONE }
    }

    /**
     * One press on an open Results sheet. Up / down move the list (clamped, silent at the ends),
     * L1 / R1 change the filter, ✕ runs the focused item's action or else the row's, △ copies the
     * list as shown and ○ goes back to the panel.
     */
    fun handleResults(
        action: GamepadAction,
        detail: NotificationDetail.Results,
        filter: ResultFilter,
        cursor: Int,
        hasFallbackAction: Boolean,
        sounds: MenuSoundSink,
        onCursorChange: (Int) -> Unit,
        onFilterChange: (ResultFilter) -> Unit,
        onItemAction: (ResultItem) -> Unit,
        onFallbackAction: () -> Unit,
        onCopy: () -> Unit,
        onClose: () -> Unit,
    ) {
        val items = visible(detail, filter)
        when (action) {
            GamepadAction.NAVIGATE_UP, GamepadAction.NAVIGATE_DOWN -> {
                val delta = if (action == GamepadAction.NAVIGATE_UP) -1 else 1
                val moved = (cursor + delta).coerceIn(0, (items.size - 1).coerceAtLeast(0))
                if (moved != cursor) { sounds.play(MenuSound.SCROLL); onCursorChange(moved) }
            }
            GamepadAction.PREV_CATEGORY, GamepadAction.NEXT_CATEGORY -> {
                val next = cycleFilter(filter, detail, if (action == GamepadAction.PREV_CATEGORY) -1 else 1)
                if (next != filter) {
                    sounds.play(MenuSound.SCROLL)
                    onFilterChange(next)
                    onCursorChange(0)
                }
            }
            GamepadAction.SELECT -> {
                val item = items.getOrNull(cursor)
                when {
                    item?.action != null -> { sounds.play(MenuSound.CONFIRM); onItemAction(item) }
                    hasFallbackAction -> { sounds.play(MenuSound.CONFIRM); onFallbackAction() }
                }
            }
            GamepadAction.OPEN_CONTEXT_MENU -> { sounds.play(MenuSound.CONFIRM); onCopy() }
            GamepadAction.BACK -> { sounds.play(MenuSound.BACK); onClose() }
            else -> Unit
        }
    }

    /**
     * One press on an open Notes sheet. Up / down scroll (by one step each), right / left open and
     * close the diagnostic, ✕ runs the row's action, △ copies the details and ○ goes back.
     */
    fun handleNotes(
        action: GamepadAction,
        hasAction: Boolean,
        hasDiagnostic: Boolean,
        sounds: MenuSoundSink,
        onScroll: (Int) -> Unit,
        onAction: () -> Unit,
        onCopy: () -> Unit,
        onToggleDiagnostic: () -> Unit,
        onClose: () -> Unit,
    ) {
        when (action) {
            GamepadAction.NAVIGATE_UP -> onScroll(-1)
            GamepadAction.NAVIGATE_DOWN -> onScroll(1)
            GamepadAction.NAVIGATE_LEFT, GamepadAction.NAVIGATE_RIGHT ->
                if (hasDiagnostic) { sounds.play(MenuSound.SCROLL); onToggleDiagnostic() }
            GamepadAction.SELECT -> if (hasAction) { sounds.play(MenuSound.CONFIRM); onAction() }
            GamepadAction.OPEN_CONTEXT_MENU -> { sounds.play(MenuSound.CONFIRM); onCopy() }
            GamepadAction.BACK -> { sounds.play(MenuSound.BACK); onClose() }
            else -> Unit
        }
    }
}

/** Plain text for △ Copy Details / Copy List. */
object DetailSheetText {

    fun notes(title: String, notes: NotificationDetail.Notes): String = buildString {
        append(title)
        notes.code?.let { append(" (").append(it).append(')') }
        notes.summary?.let { append("\n\n").append(it) }
        notes.sections.forEach { append("\n\n").append(it.heading).append('\n').append(it.body) }
        if (notes.facts.isNotEmpty()) {
            append("\n")
            notes.facts.forEach { append('\n').append(it.label).append(": ").append(it.value) }
        }
        notes.diagnostic?.let { append("\n\nDiagnostic\n").append(it) }
    }

    fun results(title: String, items: List<ResultItem>, labels: ResultsLabels): String = buildString {
        append(title)
        items.forEach { item ->
            append('\n')
            append(listOfNotNull(labels.of(item.outcome), item.primary, item.code, item.reason, item.path)
                .joinToString(" · "))
        }
    }
}

// ── Look ──────────────────────────────────────────────────────────────────────

/** The approved bucket colours: brighter than the row severities so small dots read on the card. */
fun resultOutcomeColor(outcome: ResultOutcome): Color = when (outcome) {
    ResultOutcome.FAILED -> Color(0xFFE0574B)
    ResultOutcome.SKIPPED -> Color(0xFFD69A3A)
    ResultOutcome.DONE -> Color(0xFF3FA27A)
}

private val NotesSheetWidth = 600.dp
private val ResultsSheetWidth = 720.dp
private val ResultsSheetHeight = 380.dp
private val StripeWidth = 4.dp
private val SelectedRow = Color.White.copy(alpha = 0.14f)

/** Test tags for the parts of a detail sheet. */
object PfpDetailSheetTags {
    const val NOTES = "pfp_sheet_notes"
    const val RESULTS = "pfp_sheet_results"
    const val CODE = "pfp_sheet_code"
    const val ROW = "pfp_sheet_row"
}

/**
 * The Notes sheet: why a notification happened and what to do about it (artboard 4 of the mockups).
 */
@Composable
internal fun PfpNotesSheet(
    title: String,
    meta: String?,
    detail: NotificationDetail.Notes,
    accent: Color,
    icon: ImageVector?,
    actionLabel: String?,
    diagnosticOpen: Boolean,
    copied: Boolean,
    scroll: ScrollState,
    showHints: Boolean,
    onAction: () -> Unit,
    onToggleDiagnostic: () -> Unit,
    onClose: () -> Unit,
) {
    val code = detail.code?.let(PfpErrorCode::fromId)
    val hints = buildList {
        if (actionLabel != null) add(ControllerPromptItem(GamepadAction.SELECT, actionLabel))
        add(ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, if (copied) "Copied" else "Copy Details"))
        add(ControllerPromptItem(GamepadAction.BACK, "Close"))
    }
    DetailSheetScaffold(
        width = NotesSheetWidth,
        accent = accent,
        hints = hints,
        showHints = showHints,
        onClose = onClose,
        tag = PfpDetailSheetTags.NOTES,
    ) {
        SheetHeader(icon = icon, accent = accent, title = title) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                detail.code?.let { CodeChip(it) }
                meta?.let { Text(it, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp) }
            }
        }
        Row(Modifier.weight(1f, fill = false)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(scroll)
                    .padding(bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val summary = detail.summary ?: code?.title
                summary?.let { NoteBlock("What happened", it) }
                detail.sections.forEach { NoteBlock(it.heading, it.body) }
                if (detail.facts.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.04f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        detail.facts.forEach { fact ->
                            Row {
                                Text(fact.label, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp,
                                    modifier = Modifier.width(120.dp))
                                Spacer(Modifier.width(12.dp))
                                Text(fact.value, color = Color.White.copy(alpha = 0.82f), fontSize = 12.sp)
                            }
                        }
                    }
                }
                detail.diagnostic?.let { diagnostic ->
                    Text(
                        text = (if (diagnosticOpen) "▾  " else "▸  ") + "Diagnostic details",
                        color = Color.White.copy(alpha = 0.55f),
                        fontSize = 12.sp,
                        modifier = Modifier.clickable(onClick = onToggleDiagnostic),
                    )
                    if (diagnosticOpen) {
                        Text(
                            text = diagnostic,
                            color = Color.White.copy(alpha = 0.65f),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.05f))
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                }
                if (actionLabel != null) {
                    // The touch way to the action the ✕ hint names.
                    Text(
                        text = actionLabel,
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .clickable(onClick = onAction)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    )
                }
            }
            if (scroll.maxValue > 0) {
                Spacer(Modifier.width(14.dp))
                ScrollThumb(scroll)
            }
        }
    }
}

/**
 * The Results sheet: every item the work touched, failures first, filtered with L1/R1
 * (artboards 5 and 6 of the mockups).
 */
@Composable
internal fun PfpResultsSheet(
    title: String,
    meta: String?,
    detail: NotificationDetail.Results,
    accent: Color,
    icon: ImageVector?,
    filter: ResultFilter,
    cursor: Int,
    actionLabel: String?,
    copied: Boolean,
    showHints: Boolean,
    onFilterTapped: (ResultFilter) -> Unit,
    onRowTapped: (Int) -> Unit,
    onClose: () -> Unit,
) {
    val items = PfpDetailSheetNav.visible(detail, filter)
    val focused = items.getOrNull(cursor)
    val hints = buildList {
        if (actionLabel != null) add(ControllerPromptItem(GamepadAction.SELECT, actionLabel))
        add(ControllerPromptItem(GamepadAction.OPEN_CONTEXT_MENU, if (copied) "Copied" else "Copy List"))
        add(ControllerPromptItem(listOf(GamepadAction.PREV_CATEGORY, GamepadAction.NEXT_CATEGORY), "Filter"))
        add(ControllerPromptItem(GamepadAction.BACK, "Close"))
    }
    val listState = rememberLazyListState()
    LaunchedEffect(cursor, filter) { if (cursor in items.indices) listState.animateScrollToItem(cursor) }

    DetailSheetScaffold(
        width = ResultsSheetWidth,
        height = ResultsSheetHeight,
        accent = accent,
        hints = hints,
        showHints = showHints,
        onClose = onClose,
        tag = PfpDetailSheetTags.RESULTS,
    ) {
        SheetHeader(icon = icon, accent = accent, title = title, trailing = meta) {}

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ResultOutcome.entries.forEach { outcome ->
                val n = detail.count(outcome)
                if (n > 0) SummaryChip(outcome, "$n ${detail.labels.of(outcome)}")
            }
        }

        Column {
            Row(
                modifier = Modifier.height(28.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ShoulderBadge("L1")
                PfpDetailSheetNav.filters(detail).forEach { tab ->
                    FilterTab(
                        label = when (tab) {
                            ResultFilter.ALL -> "All"
                            ResultFilter.FAILED -> detail.labels.failed
                            ResultFilter.SKIPPED -> detail.labels.skipped
                            ResultFilter.DONE -> detail.labels.done
                        },
                        selected = tab == filter,
                        onClick = { onFilterTapped(tab) },
                    )
                }
                ShoulderBadge("R1")
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.18f)))
        }

        Row(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.width(390.dp).fillMaxHeight().padding(end = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(items) { index, item ->
                    ResultRow(item, detail.labels, selected = index == cursor, onClick = { onRowTapped(index) })
                }
                if (detail.truncated > 0 && filter == ResultFilter.ALL) {
                    item {
                        Text("+${detail.truncated} more", color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp))
                    }
                }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().padding(bottom = 16.dp).background(Color.White.copy(alpha = 0.12f)))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    .padding(start = 18.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (focused != null) ResultDetailPane(focused, detail.labels)
            }
        }
    }
}

@Composable
private fun ResultDetailPane(item: ResultItem, labels: ResultsLabels) {
    val code = item.code?.let(PfpErrorCode::fromId)
    Text(item.primary, color = Color.White.copy(alpha = 0.95f), fontSize = 16.sp, fontWeight = FontWeight.Medium)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(resultOutcomeColor(item.outcome)))
        Text(item.badge ?: labels.of(item.outcome), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
        item.code?.let { CodeChip(it) }
    }
    (item.reason ?: code?.why)?.let {
        Text(it, color = Color.White.copy(alpha = 0.70f), fontSize = 13.sp, lineHeight = 20.sp)
    }
    item.path?.let {
        Text(
            it,
            color = Color.White.copy(alpha = 0.65f),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.05f))
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
    }
    if (code != null && item.outcome != ResultOutcome.DONE) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("What you can do", color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(code.whatYouCanDo, color = Color.White.copy(alpha = 0.70f), fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}

// ── Shared pieces ─────────────────────────────────────────────────────────────

@Composable
private fun DetailSheetScaffold(
    width: Dp,
    accent: Color,
    hints: List<ControllerPromptItem>,
    showHints: Boolean,
    onClose: () -> Unit,
    tag: String,
    height: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ModalScrim)
            .pointerInput(Unit) { detectTapGestures { onClose() } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .testTag(tag)
                    .weight(1f, fill = false)
                    .width(width)
                    .then(if (height != null) Modifier.heightIn(max = height) else Modifier)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ModalSurface)
                    .pointerInput(Unit) { detectTapGestures { } },
            ) {
                Column(
                    modifier = Modifier
                        .then(if (height != null) Modifier.height(height) else Modifier)
                        .padding(start = 28.dp, end = 24.dp, top = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = content,
                )
                // The approved severity stripe, down the card's left edge.
                Box(Modifier.matchParentSize()) {
                    Box(Modifier.width(StripeWidth).fillMaxHeight().background(accent))
                }
            }
            if (showHints) ControllerHintBar(items = hints)
        }
    }
}

@Composable
private fun SheetHeader(
    icon: ImageVector?,
    accent: Color,
    title: String,
    trailing: String? = null,
    meta: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.22f))
                    .border(1.dp, accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) Icon(icon, contentDescription = null, tint = Color.White.copy(alpha = 0.92f),
                    modifier = Modifier.size(17.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = Color.White.copy(alpha = 0.92f), fontSize = 19.sp, fontWeight = FontWeight.Light,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                meta()
            }
            trailing?.let { Text(it, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.30f)))
    }
}

@Composable
private fun NoteBlock(heading: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(heading, color = Color.White.copy(alpha = 0.92f), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(body, color = ModalSubtext, fontSize = 14.sp, lineHeight = 21.sp)
    }
}

@Composable
private fun CodeChip(code: String) {
    Text(
        text = code,
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.4.sp,
        modifier = Modifier
            .testTag(PfpDetailSheetTags.CODE)
            .clip(RoundedCornerShape(4.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun SummaryChip(outcome: ResultOutcome, label: String) {
    val color = resultOutcomeColor(outcome)
    Row(
        modifier = Modifier
            .height(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(color.copy(alpha = 0.14f))
            .border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(11.dp))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Text(label, color = Color.White.copy(alpha = 0.9f), fontSize = 12.sp)
    }
}

@Composable
private fun ShoulderBadge(label: String) {
    Text(
        text = label,
        color = Color.White.copy(alpha = 0.75f),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(4.dp))
            .padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

@Composable
private fun FilterTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick).padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            label,
            color = Color.White.copy(alpha = if (selected) 0.95f else 0.55f),
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
        Box(
            Modifier
                .width(IntrinsicTabUnderline)
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(if (selected) Color.White.copy(alpha = 0.9f) else Color.Transparent),
        )
    }
}

private val IntrinsicTabUnderline = 24.dp

@Composable
private fun ResultRow(item: ResultItem, labels: ResultsLabels, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .testTag(PfpDetailSheetTags.ROW)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) SelectedRow else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(resultOutcomeColor(item.outcome)))
        Text(
            item.primary,
            color = Color.White.copy(alpha = if (selected) 0.95f else 0.88f),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(item.badge ?: labels.of(item.outcome), color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun ScrollThumb(scroll: ScrollState) {
    Box(
        Modifier
            .width(3.dp)
            .fillMaxHeight()
            .padding(bottom = 18.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Color.White.copy(alpha = 0.08f)),
    ) {
        val total = scroll.maxValue + 1000f
        val fraction = (1000f / total).coerceIn(0.15f, 1f)
        val offset = scroll.value / scroll.maxValue.toFloat().coerceAtLeast(1f)
        Column(Modifier.fillMaxHeight()) {
            Spacer(Modifier.weight((offset * (1f - fraction)).coerceAtLeast(0.0001f)))
            Box(Modifier.fillMaxWidth().weight(fraction).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.4f)))
            Spacer(Modifier.weight(((1f - offset) * (1f - fraction)).coerceAtLeast(0.0001f)))
        }
    }
}
