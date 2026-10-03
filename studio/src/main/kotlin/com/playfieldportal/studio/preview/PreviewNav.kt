package com.playfieldportal.studio.preview

import androidx.compose.ui.input.key.Key
import com.playfieldportal.themekit.XmbLayoutAdjust
import com.playfieldportal.themekit.XmbLayoutSpec

/*
 * The interactive preview's navigation and geometry, both pure so they can be tested without a
 * window. [PreviewNav] is the reducer; [PreviewGeometry] is the launcher's cross layout math
 * (XMBShell / XMBCategoryBar / XMBItemList) over plain dp floats.
 */

/**
 * Where the preview cursor is: the selected category, the remembered row of every category, and the
 * drill stack. [drill] holds the selected row of each drilled list: entering a node pushes 0, so the
 * list on screen is the children of the node chosen one level up.
 */
data class PreviewNavState(
    val category: Int = SampleContent.SELECTED_CATEGORY,
    val rootRows: List<Int> = List(SampleContent.categories.size) {
        if (it == SampleContent.SELECTED_CATEGORY) SampleContent.SELECTED_ROW else 0
    },
    val drill: List<Int> = emptyList(),
    /** A one-line status for the last Enter on a leaf; any other action clears it. */
    val message: String? = null,
    /** The open options / Games filter menu, which owns the keyboard while it is up. */
    val flyout: FlyoutState? = null,
    /** The Games filter: the live search term and the sort, kept while the game list stays open. */
    val filter: GamesFilter = GamesFilter(),
    /** The open search field, remembering the term it opened with so Esc can restore it. */
    val search: SearchField? = null,
) {
    val isDrilled: Boolean get() = drill.isNotEmpty()

    /** Selected row of the list currently on screen. */
    val currentIndex: Int get() = drill.lastOrNull() ?: rootRows[category]

    companion object {
        /** The frame `preview.png` shows: Video selected, its second row under the bar. */
        val HOME = PreviewNavState()
    }
}

/** An open Games search field; the live term itself is [PreviewNavState.filter]. */
data class SearchField(val termOnOpen: String)

sealed interface PreviewNavAction {
    data object Left : PreviewNavAction
    data object Right : PreviewNavAction
    data object Up : PreviewNavAction
    data object Down : PreviewNavAction
    data object Enter : PreviewNavAction
    data object Back : PreviewNavAction
    data class ClickCategory(val index: Int) : PreviewNavAction
    data class ClickRow(val index: Int) : PreviewNavAction

    /** A tap on the drilled-into card in the left column: backs out one level, as on the device. */
    data object ClickSibling : PreviewNavAction

    /** Tab / Y / right-click: the selected row's options (a second press closes them). */
    data object OpenOptions : PreviewNavAction

    /** X: the Games filter, in the Games category only. */
    data object OpenFilter : PreviewNavAction

    data class ClickFlyoutRow(val index: Int) : PreviewNavAction

    /** A click on the scrim behind the flyout. */
    data object DismissFlyout : PreviewNavAction

    /** Every keystroke in the search field: the list behind it re-filters live. */
    data class SetSearch(val text: String) : PreviewNavAction
}

/** What is on screen: the current list, and while drilled the parent column beside it. */
data class PreviewView(
    val rows: List<SampleContent.Row>,
    val selected: Int,
    val siblings: List<SampleContent.Row>?,
    val siblingIndex: Int,
)

object PreviewNav {

    /** The lists from the category root down to the one on screen. */
    private fun lists(state: PreviewNavState): List<List<SampleContent.Row>> {
        val out = ArrayList<List<SampleContent.Row>>(state.drill.size + 1)
        var list = SampleContent.rootRows(state.category)
        out += list
        var sel = state.rootRows[state.category]
        for (picked in state.drill) {
            list = PreviewFilter.apply(list.getOrNull(sel)?.children.orEmpty(), state.filter)
            out += list
            sel = picked
        }
        return out
    }

    fun view(state: PreviewNavState): PreviewView {
        val all = lists(state)
        val rows = all.last()
        val selected = state.currentIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        if (all.size == 1) return PreviewView(rows, selected, siblings = null, siblingIndex = -1)
        val siblingIndex = if (all.size == 2) state.rootRows[state.category] else state.drill[all.size - 3]
        return PreviewView(rows, selected, siblings = all[all.size - 2], siblingIndex = siblingIndex)
    }

    /** Esc has somewhere to go: out of a search or a menu, or up a drill level. */
    fun canGoBack(state: PreviewNavState): Boolean = state.isDrilled || state.flyout != null || state.search != null

    /** The row under the cursor in the list on screen, or null for an empty (fully filtered) list. */
    fun selectedRow(state: PreviewNavState): SampleContent.Row? = view(state).let { it.rows.getOrNull(it.selected) }

    private val gamesCategory: Int = SampleContent.categories.indexOfFirst { it.slotKey == "catbar_games" }

    /** A game list is on screen: the Games category drilled into a console. */
    fun inGameList(state: PreviewNavState): Boolean = state.category == gamesCategory && state.isDrilled

    /** The status strip's Filter chip: only a game list carries it. */
    fun statusLabel(state: PreviewNavState): String? =
        if (inGameList(state)) PreviewFilter.chipLabel(state.filter) else null

    /** The selected category's `catbar_*` slot key — the icon picker's "On screen" hook. */
    fun categoryKey(state: PreviewNavState): String = SampleContent.categories[state.category].slotKey

    /** Slot keys of every row on screen (children plus the parent column while drilled). */
    fun shownSlotKeys(state: PreviewNavState): Set<String> {
        val v = view(state)
        return buildSet {
            add(categoryKey(state))
            if (state.flyout != null) add("menu_check")
            v.rows.forEach { add(it.slotKey) }
            v.siblings?.forEach { add(it.slotKey) }
        }
    }

    fun actionFor(key: Key): PreviewNavAction? = when (key) {
        Key.DirectionLeft -> PreviewNavAction.Left
        Key.DirectionRight -> PreviewNavAction.Right
        Key.DirectionUp -> PreviewNavAction.Up
        Key.DirectionDown -> PreviewNavAction.Down
        Key.Enter, Key.NumPadEnter -> PreviewNavAction.Enter
        Key.Escape, Key.Backspace -> PreviewNavAction.Back
        Key.Tab, Key.Y -> PreviewNavAction.OpenOptions
        Key.X -> PreviewNavAction.OpenFilter
        else -> null
    }

    fun reduce(state: PreviewNavState, action: PreviewNavAction): PreviewNavState {
        val s = if (state.message != null) state.copy(message = null) else state
        s.search?.let { return reduceSearch(s, it, action) }
        s.flyout?.let { return reduceFlyout(s, it, action) }
        return when (action) {
            PreviewNavAction.Left -> stepCategory(s, -1)
            PreviewNavAction.Right -> stepCategory(s, +1)
            PreviewNavAction.Up -> stepRow(s, -1)
            PreviewNavAction.Down -> stepRow(s, +1)
            PreviewNavAction.Enter -> enter(s)
            PreviewNavAction.Back, PreviewNavAction.ClickSibling -> if (s.isDrilled) popDrill(s) else s
            is PreviewNavAction.ClickCategory ->
                if (s.isDrilled || action.index !in SampleContent.categories.indices) s else switchCategory(s, action.index)
            is PreviewNavAction.ClickRow -> clickRow(s, action.index)
            PreviewNavAction.OpenOptions ->
                if (selectedRow(s) == null) s else s.copy(flyout = FlyoutState(FlyoutKind.OPTIONS))
            PreviewNavAction.OpenFilter ->
                if (s.category == gamesCategory) s.copy(flyout = FlyoutState(FlyoutKind.GAMES_FILTER)) else s
            is PreviewNavAction.ClickFlyoutRow, PreviewNavAction.DismissFlyout, is PreviewNavAction.SetSearch -> s
        }
    }

    // Categories are locked while drilled: the bar has collapsed onto the active column.
    private fun stepCategory(s: PreviewNavState, delta: Int): PreviewNavState =
        if (s.isDrilled) s else switchCategory(s, (s.category + delta).coerceIn(0, SampleContent.categories.lastIndex))

    // The search term belongs to the list it was typed against; a sort is a standing choice and stays.
    private fun switchCategory(s: PreviewNavState, category: Int): PreviewNavState =
        if (category == s.category) s else s.copy(category = category, filter = s.filter.copy(term = ""))

    private fun popDrill(s: PreviewNavState): PreviewNavState =
        s.copy(drill = s.drill.dropLast(1), filter = s.filter.copy(term = ""))

    // ── Options flyout and Games filter ──────────────────────────────────────

    private fun reduceFlyout(s: PreviewNavState, f: FlyoutState, action: PreviewNavAction): PreviewNavState {
        val rows = PreviewFlyout.rows(s)
        return when (action) {
            PreviewNavAction.Up -> s.copy(flyout = f.copy(cursor = (f.cursor - 1).coerceAtLeast(0)))
            PreviewNavAction.Down ->
                s.copy(flyout = f.copy(cursor = (f.cursor + 1).coerceAtMost((rows.size - 1).coerceAtLeast(0))))
            PreviewNavAction.Enter -> activate(s, f, rows, f.cursor)
            is PreviewNavAction.ClickFlyoutRow -> if (action.index in rows.indices) activate(s, f, rows, action.index) else s
            PreviewNavAction.Back ->
                if (f.menu == FlyoutMenu.ROOT) s.copy(flyout = null)
                else s.copy(flyout = f.copy(menu = FlyoutMenu.ROOT, cursor = f.rootCursor))
            PreviewNavAction.DismissFlyout, PreviewNavAction.OpenOptions -> s.copy(flyout = null)
            // Left / Right do nothing in a menu; the list, bar and filter behind the scrim stay out of reach.
            else -> s
        }
    }

    private fun activate(s: PreviewNavState, f: FlyoutState, rows: List<PreviewMenuRow>, index: Int): PreviewNavState {
        val row = rows.getOrNull(index) ?: return s
        val closed = s.copy(flyout = null)
        return when (f.kind) {
            FlyoutKind.OPTIONS ->
                if (row.opensSubmenu) s.copy(flyout = f.copy(menu = FlyoutMenu.SUBMENU, cursor = 0, rootCursor = index))
                else closed.copy(message = "${row.label} happens on the device")
            FlyoutKind.GAMES_FILTER -> when {
                row.id == PreviewFilter.SORT -> {
                    // A group opens on its active choice.
                    val checked = PreviewFilter.rows(s.filter, FlyoutMenu.SORT).indexOfFirst { it.checked }.coerceAtLeast(0)
                    s.copy(flyout = f.copy(menu = FlyoutMenu.SORT, cursor = checked, rootCursor = index))
                }
                row.id == PreviewFilter.SEARCH -> closed.copy(search = SearchField(termOnOpen = s.filter.term))
                row.id == PreviewFilter.CLEAR -> withTerm(closed, "")
                row.id.startsWith(PreviewFilter.SORT_PREFIX) -> {
                    val sort = GameSort.entries.firstOrNull { it.name == row.id.removePrefix(PreviewFilter.SORT_PREFIX) }
                    if (sort == null) closed else withFilter(closed, closed.filter.copy(sort = sort))
                }
                else -> closed
            }
        }
    }

    private fun reduceSearch(s: PreviewNavState, field: SearchField, action: PreviewNavAction): PreviewNavState = when (action) {
        is PreviewNavAction.SetSearch -> withTerm(s, action.text)
        PreviewNavAction.Enter -> s.copy(search = null) // the term is already live; nothing to commit
        PreviewNavAction.Back -> withTerm(s, field.termOnOpen).copy(search = null)
        else -> s
    }

    private fun withTerm(s: PreviewNavState, term: String): PreviewNavState = withFilter(s, s.filter.copy(term = term))

    /** A filter change sends the cursor back to the top of the game list, as the launcher does. */
    private fun withFilter(s: PreviewNavState, filter: GamesFilter): PreviewNavState {
        if (filter == s.filter) return s
        val next = s.copy(filter = filter)
        return if (inGameList(next)) withRow(next, 0) else next
    }

    private fun stepRow(s: PreviewNavState, delta: Int): PreviewNavState =
        withRow(s, (s.currentIndex + delta).coerceIn(0, (view(s).rows.size - 1).coerceAtLeast(0)))

    private fun withRow(s: PreviewNavState, row: Int): PreviewNavState =
        if (s.isDrilled) s.copy(drill = s.drill.dropLast(1) + row)
        else s.copy(rootRows = s.rootRows.toMutableList().also { it[s.category] = row })

    private fun enter(s: PreviewNavState): PreviewNavState {
        val v = view(s)
        val node = v.rows.getOrNull(v.selected) ?: return s
        return if (node.children.isNotEmpty()) s.copy(drill = s.drill + 0)
        else s.copy(message = "${node.title} opens on the device")
    }

    private fun clickRow(s: PreviewNavState, index: Int): PreviewNavState {
        val rows = view(s).rows
        if (index !in rows.indices) return s
        return if (index == s.currentIndex) enter(s) else withRow(s, index)
    }
}

/**
 * The launcher's cross layout in dp, mirrored from XMBShell.kt / XMBCategoryBar.kt / XMBItemList.kt.
 * [layoutSize] is the dp box the cross lays out in once the layout-adjust scale divides the 832x468
 * base (the launcher multiplies density by that scale).
 */
object PreviewGeometry {
    // XMBShell XMB_BASELINE_WIDTH_DP / XMB_BASELINE_HEIGHT_DP (the Thor at 1920x1080).
    const val BASE_WIDTH = 832f
    const val BASE_HEIGHT = 468f

    // XMBCategoryBar.CategorySlotWidth, XMBShell.CAT_BAR_HEIGHT, XMBItemList.ROW_HEIGHT.
    const val CATEGORY_SLOT = 124f
    const val CAT_BAR_HEIGHT = 112f
    const val ROW_HEIGHT = 88f

    // XMBShell.DRILL_CROSSBAR_LEFT_MARGIN and XMBItemList.DRILL_GAME_COLUMN_LEFT.
    const val DRILL_LEFT_MARGIN = 16f
    const val DRILL_CHILD_COLUMN_LEFT = 138f

    /** XMBCategoryBar.XmbLeftAnchor: one slot plus the spec's extra shift. */
    fun leftAnchor(spec: XmbLayoutSpec): Float = CATEGORY_SLOT + spec.leftAnchorExtraDp

    /** XMBItemList.LEADING_ICON_CENTER: 18 dp row padding plus half the icon slot. */
    fun leadingIconCenter(spec: XmbLayoutSpec): Float = 18f + spec.itemIconSlotDp / 2f

    /** XMBShell.columnBaseInset: lands every row's icon centre on the caticon's vertical line. */
    fun columnBaseInset(spec: XmbLayoutSpec): Float =
        leftAnchor(spec) + CATEGORY_SLOT / 2f - leadingIconCenter(spec)

    /** The (width, height) in dp of the box the cross lays out in. */
    fun layoutSize(adjust: XmbLayoutAdjust): Pair<Float, Float> =
        (BASE_WIDTH / adjust.scale) to (BASE_HEIGHT / adjust.scale)

    /** Horizontal shift of the whole cross: pinned to the drill margin while drilled. */
    fun hShift(spec: XmbLayoutSpec, drilled: Boolean, adjust: XmbLayoutAdjust, layoutWidth: Float): Float =
        if (drilled) DRILL_LEFT_MARGIN - columnBaseInset(spec) else layoutWidth * adjust.barLeftFraction

    fun startPad(spec: XmbLayoutSpec, drilled: Boolean, adjust: XmbLayoutAdjust, layoutWidth: Float): Float =
        columnBaseInset(spec) + hShift(spec, drilled, adjust, layoutWidth)

    /** X of the drilled children column: the item column's start pad plus the sibling column's width. */
    fun childColumnLeft(spec: XmbLayoutSpec, adjust: XmbLayoutAdjust, layoutWidth: Float): Float =
        startPad(spec, drilled = true, adjust, layoutWidth) + DRILL_CHILD_COLUMN_LEFT

    /** The bar's selection slide: one slot per category, glides on the launcher. */
    fun barSlide(selected: Int): Float = -CATEGORY_SLOT * selected

    /** X of the category row's first slot (the anchor snaps; only [barSlide] glides). */
    fun barLeft(
        spec: XmbLayoutSpec, selected: Int, drilled: Boolean, adjust: XmbLayoutAdjust, layoutWidth: Float,
    ): Float = (leftAnchor(spec) + hShift(spec, drilled, adjust, layoutWidth)).coerceAtLeast(0f) + barSlide(selected)

    /** Categories laid out: drilled in, everything right of the active one is dropped. */
    fun visibleCategoryCount(selected: Int, count: Int, drilled: Boolean): Int =
        if (drilled && selected in 0 until count) selected + 1 else count

    /** Height of the cross box: the layout height under the spec's content top padding. */
    fun crossHeight(spec: XmbLayoutSpec, layoutHeight: Float): Float = layoutHeight - spec.contentTopPaddingDp

    fun barTop(spec: XmbLayoutSpec, adjust: XmbLayoutAdjust, layoutHeight: Float): Float =
        crossHeight(spec, layoutHeight) * adjust.barTopFraction

    /** Where the selected row's top sits: directly under the bar. */
    fun anchorTop(spec: XmbLayoutSpec, adjust: XmbLayoutAdjust, layoutHeight: Float): Float =
        barTop(spec, adjust, layoutHeight) + CAT_BAR_HEIGHT

    /**
     * What the launcher resolves when the user has not tuned the layout: scale 1, no shift, and the
     * theme's own bar line. With "Preview with my layout" on, the stored values replace all three.
     */
    fun effectiveAdjust(spec: XmbLayoutSpec, enabled: Boolean, stored: XmbLayoutAdjust): XmbLayoutAdjust =
        if (enabled) stored else XmbLayoutAdjust(scale = 1f, barLeftFraction = 0f, barTopFraction = spec.barTopFraction)
}
