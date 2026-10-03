package com.playfieldportal.studio.preview

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The Games filter (XMBViewModel.gamesFilterRows / currentSortLabel / GameSearchField), as pure
 * rules plus the small composables that draw them.
 * Sort and search apply to the sample game lists only.
 */

/** XMBViewModel.GAME_SORTS, in the menu's order. */
enum class GameSort(val label: String) {
    TITLE("Title"),
    RECENT_PLAYED("Recently Played"),
    DATE_ADDED("Date Added"),
}

data class GamesFilter(val term: String = "", val sort: GameSort = GameSort.TITLE) {
    val hasTerm: Boolean get() = term.isNotBlank()
}

object PreviewFilter {

    const val SEARCH = "games_filter_search"
    const val SORT = "games_filter_sort"
    const val CLEAR = "games_filter_clear"
    const val SORT_PREFIX = "games_filter_sort_"

    /**
     * gamesFilterRows: the root names each list with its current choice (the value is its own
     * field, pinned right) and offers Clear Search only while a term is set; the Sort group checks
     * the active mode.
     */
    fun rows(filter: GamesFilter, menu: FlyoutMenu): List<PreviewMenuRow> = when (menu) {
        FlyoutMenu.SORT -> GameSort.entries.map {
            PreviewMenuRow(SORT_PREFIX + it.name, it.label, checked = it == filter.sort)
        }
        else -> buildList {
            val term = filter.term.trim()
            add(PreviewMenuRow(SEARCH, "Search", value = if (term.isBlank()) "None" else "\"$term\""))
            add(PreviewMenuRow(SORT, "Sort", value = filter.sort.label))
            if (term.isNotBlank()) add(PreviewMenuRow(CLEAR, "Clear Search"))
        }
    }

    /** currentSortLabel: the status chip names the menu it opens and carries the active term. */
    fun chipLabel(filter: GamesFilter): String {
        val term = filter.term.trim()
        return if (term.isBlank()) "Filter: ${filter.sort.label}" else "Filter: \"$term\" · ${filter.sort.label}"
    }

    // Stand-ins for lastPlayedAt / id on the sample games; Title is the declared order.
    private val recentOrder = listOf("Shiba Run", "Crossbar Racing", "Portal Quest", "Memory Card Blues")
    private val addedOrder = listOf("Portal Quest", "Shiba Run", "Memory Card Blues", "Crossbar Racing")

    /** gamesForDisplay: a blank query filters nothing; otherwise a case-insensitive title match, then the sort. */
    fun apply(rows: List<SampleContent.Row>, filter: GamesFilter): List<SampleContent.Row> {
        if (rows.none { it.isGame }) return rows
        val q = filter.term.trim().lowercase()
        val matched = if (q.isBlank()) rows else rows.filter { q in it.title.lowercase() }
        return when (filter.sort) {
            GameSort.TITLE -> matched.sortedBy { it.title }
            GameSort.RECENT_PLAYED -> matched.sortedBy { recentOrder.indexOf(it.title).let { i -> if (i < 0) Int.MAX_VALUE else i } }
            GameSort.DATE_ADDED -> matched.sortedBy { addedOrder.indexOf(it.title).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        }
    }
}

// ── Rendering ────────────────────────────────────────────────────────────────

/**
 * GameSearchField: a top-right field under the status strip (y 38 dp, min width 320) that takes the
 * caret on open. Enter keeps the term, Esc restores the one it opened with.
 */
@Composable
fun BoxScope.GameSearchBox(text: String, onTextChange: (String) -> Unit, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(end = 16.dp, top = 38.dp)
            .widthIn(min = 320.dp)
            .background(Color(0xE00A1428), RoundedCornerShape(6.dp))
            .border(1.dp, Color.White.copy(alpha = 0.28f), RoundedCornerShape(6.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        SearchGlyph()
        Spacer(Modifier.size(10.dp))
        BasicTextField(
            value = text,
            onValueChange = onTextChange,
            singleLine = true,
            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
            cursorBrush = SolidColor(Color.White),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text("Search games…", color = Color.White.copy(alpha = 0.45f), fontSize = 16.sp)
                    inner()
                }
            },
            modifier = Modifier
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.Enter, Key.NumPadEnter -> { onConfirm(); true }
                        Key.Escape -> { onCancel(); true }
                        else -> false
                    }
                },
        )
    }
}

/** The hand-drawn magnifier from GameSearchField. */
@Composable
private fun SearchGlyph() {
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.8f.dp.toPx()
        val r = size.width * 0.30f
        val cx = size.width * 0.42f
        val cy = size.height * 0.42f
        drawCircle(Color.White.copy(alpha = 0.7f), radius = r, center = Offset(cx, cy), style = Stroke(stroke))
        drawLine(
            color = Color.White.copy(alpha = 0.7f),
            start = Offset(cx + r * 0.7f, cy + r * 0.7f),
            end = Offset(size.width * 0.92f, size.height * 0.92f),
            strokeWidth = stroke,
        )
    }
}
