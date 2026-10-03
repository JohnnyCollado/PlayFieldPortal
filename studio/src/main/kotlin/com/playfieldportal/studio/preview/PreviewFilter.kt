package com.playfieldportal.studio.preview

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.playfieldportal.themekit.XmbLayoutAdjust
import com.playfieldportal.themekit.XmbLayoutSpec
import kotlinx.coroutines.delay

/*
 * The Games filter (XMBViewModel.gamesFilterRows / currentSortLabel / GameSearchField) and the
 * focused game's PIC0 logo (XMBShell), as pure rules plus the small composables that draw them.
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

/** XMBShell's PIC0 logo: centre-right, appears a beat after focus settles, hides instantly. */
object PreviewPic0 {
    const val DELAY_MS = 650L
    private const val FADE_IN_MS = 500
    const val WIDTH_FRACTION = 0.30f
    const val HEIGHT_FRACTION = 0.38f
    const val END_PADDING = 44f

    /** Fade-in only: any focus move snaps it away, so the next logo is never glimpsed early. */
    fun fadeMs(visible: Boolean): Int = if (visible) FADE_IN_MS else 0

    /** The focused game's name (the stand-in for its clear logo), or null when nothing logo-bearing is focused. */
    fun logoTitle(nav: PreviewNavState): String? = PreviewNav.selectedRow(nav)?.takeIf { it.isGame }?.title

    /**
     * Vertical offset of the logo's centre from the screen's centre, in dp of the layout box. At the
     * root the logo sits on the centre line; drilled it follows the active card's row, kept inside
     * [19 %, 81 %] so a low crossbar cannot push it off the bottom.
     */
    fun centerOffsetDp(spec: XmbLayoutSpec, adjust: XmbLayoutAdjust, layoutHeight: Float, drilled: Boolean): Float {
        if (!drilled) return 0f
        val crossHeight = layoutHeight - spec.contentTopPaddingDp
        val anchorTop = crossHeight * adjust.barTopFraction + PreviewGeometry.CAT_BAR_HEIGHT
        val rowCenter = spec.contentTopPaddingDp + anchorTop + PreviewGeometry.ROW_HEIGHT / 2
        return rowCenter.coerceIn(layoutHeight * 0.19f, layoutHeight * 0.81f) - layoutHeight / 2
    }
}

// ── Rendering ────────────────────────────────────────────────────────────────

private val LogoShadow = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 3f), 10f)

/** The focused game's logo, faded in after [PreviewPic0.DELAY_MS]; drawn as a wordmark of its name. */
@Composable
fun Pic0Logo(model: XmbPreviewModel, nav: PreviewNavState) {
    val title = PreviewPic0.logoTitle(nav)
    var visible by remember(title) { mutableStateOf(false) }
    LaunchedEffect(title) {
        if (title != null) {
            delay(PreviewPic0.DELAY_MS)
            visible = true
        }
    }
    val fade = PreviewPic0.fadeMs(visible)
    val alpha by animateFloatAsState(
        targetValue = if (visible && title != null) 1f else 0f,
        animationSpec = if (fade == 0) snap() else tween(fade),
        label = "previewPic0Fade",
    )
    if (title == null || alpha <= 0f) return
    val (_, layoutH) = PreviewGeometry.layoutSize(model.layoutAdjust)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        val offset = PreviewPic0.centerOffsetDp(model.layout, model.layoutAdjust, layoutH, nav.isDrilled)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxWidth(PreviewPic0.WIDTH_FRACTION)
                .fillMaxHeight(PreviewPic0.HEIGHT_FRACTION)
                .offset(y = offset.dp)
                .padding(end = PreviewPic0.END_PADDING.dp)
                .alpha(alpha),
        ) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                maxLines = 3,
                style = TextStyle(shadow = LogoShadow),
            )
        }
    }
}

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
