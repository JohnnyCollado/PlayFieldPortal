package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameCollection
import com.playfieldportal.core.domain.model.GameContentType
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.domain.model.IconDisplayMode
import com.playfieldportal.core.domain.model.MemoryCard
import com.playfieldportal.core.ui.sound.MenuSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the library-shelf game picker's rules (GamePickerLogic): which shelves exist, the two
 * levels (shelf list → grid; A descends, B ascends, B on the list closes), D-pad step-through on
 * the list, XMB-sized tiles in free-flowing rows with nearest-tile up/down, selection keyed by id rather than by tile, whole-shelf
 * toggling, the single picker-wide view, add/remove counts that match
 * XMBViewModel.confirmGamePicker, and the menu sound for each input.
 */
class GamePickerLogicTest {

    private fun game(id: Long, platform: String, type: GameContentType = GameContentType.GAME) =
        Game(id = id, title = "Game $id", platformId = platform, contentType = type)

    private fun card(platform: String, name: String = platform.uppercase()) =
        MemoryCard(platformId = platform, displayName = name)

    private fun collection(id: Long) = GameCollection(id = id, name = "Card $id")

    // 8 PS2 games (two ICON0 rows on a six-wide pane: 6 + 2), 3 SNES games, one Android app on PS2's card that must never
    // show, and two custom memory cards.
    private val cards = listOf(card("ps2"), card("snes"), card("gba"))
    private val games = (1L..8L).map { game(it, "ps2") } +
        (9L..11L).map { game(it, "snes") } +
        game(99, "ps2", GameContentType.ANDROID_APP)
    private val collections = listOf(collection(500), collection(501))

    private fun state(
        preselected: Set<Long> = setOf(1, 9),
        movable: Set<Long>? = null,
    ): GamePickerState {
        val shelves = buildShelves(cards, games, collections, preselected, movable, "RPGs")
        return GamePickerState(shelves = shelves, selectedGameIds = preselected, preselectedGameIds = preselected)
    }

    // Exactly six ICON0 tiles (126 + 2 × 4 pad = 134 each, 8 apart): 6 × 134 + 5 × 8.
    private val sixIcon0Wide = 844f

    /** The PS2 shelf in ICON0, six tiles to a row, cursor in its grid. */
    private fun onPs2() = state().copy(
        shelfIndex = 1,
        focusZone = PickerZone.GRID,
        viewMode = IconDisplayMode.ICON0,
        shelfWidthDp = sixIcon0Wide,
    )

    // The cursor runs on the unified navigation engine through GamePickerNav; these route the
    // tests' inputs through it the way GamePickerViewModel does.
    private val nav = GamePickerNav()

    private fun GamePickerState.move(action: GamepadAction) = nav.move(this, action)
    private fun GamePickerState.press(vararg actions: GamepadAction) = actions.fold(this) { s, a -> nav.move(s, a) }
    private fun GamePickerState.activate() = nav.activate(this)
    private fun GamePickerState.back() = nav.back(this)
    private fun GamePickerState.tapTile(index: Int) = nav.tapTile(this, index)

    // ── Shelves ──────────────────────────────────────────────────────────────

    @Test
    fun `shelves are the category, then each console with games, then custom cards`() {
        val shelves = state().shelves
        assertEquals(
            listOf(IN_CATEGORY_SHELF_KEY, platformShelfKey("ps2"), platformShelfKey("snes"), CARDS_SHELF_KEY),
            shelves.map { it.key },
        )
        assertEquals("In RPGs", shelves[0].title)
        // GBA has no games, so it has no shelf.
        assertTrue(shelves.none { it.key == platformShelfKey("gba") })
    }

    @Test
    fun `console shelves drop the memory card suffix`() {
        assertEquals("Game Boy Advance", shelfTitle(card("gba", "Game Boy Advance Memory Card")))
        assertEquals("Memory Card", shelfTitle(card("x", "Memory Card")))
        assertEquals("PS2", shelfTitle(card("ps2")))
    }

    @Test
    fun `standard apps never join a shelf`() {
        val ps2 = state().shelves.first { it.key == platformShelfKey("ps2") } as GameShelf
        assertEquals((1L..8L).toList(), ps2.games.map { it.id })
    }

    @Test
    fun `no in-category shelf when the category is empty`() {
        assertEquals(platformShelfKey("ps2"), state(preselected = emptySet()).shelves.first().key)
    }

    @Test
    fun `movable ids limit the custom cards and an empty set hides the shelf`() {
        val limited = state(movable = setOf(501)).shelves.last() as CardShelf
        assertEquals(listOf(501L), limited.cards.map { it.id })
        assertTrue(state(movable = emptySet()).shelves.none { it.key == CARDS_SHELF_KEY })
    }

    // ── Levels: the shelf list, then the grid ────────────────────────────────

    @Test
    fun `the picker opens on the shelf list`() {
        assertEquals(PickerZone.RAIL, GamePickerState().focusZone)
    }

    @Test
    fun `the list steps through shelves with up and down and clamps at both ends`() {
        val list = state()
        assertEquals(1, list.press(GamepadAction.NAVIGATE_DOWN).shelfIndex)
        assertEquals(2, list.press(GamepadAction.NAVIGATE_DOWN, GamepadAction.NAVIGATE_DOWN).shelfIndex)
        assertEquals(0, list.press(GamepadAction.NAVIGATE_UP).shelfIndex)
        val last = list.copy(shelfIndex = list.shelves.lastIndex)
        assertEquals(list.shelves.lastIndex, last.press(GamepadAction.NAVIGATE_DOWN).shelfIndex)
    }

    @Test
    fun `left and right do nothing on the list`() {
        val list = state()
        assertEquals(list, list.press(GamepadAction.NAVIGATE_LEFT))
        assertEquals(list, list.press(GamepadAction.NAVIGATE_RIGHT))
    }

    @Test
    fun `A descends into the grid without toggling and B climbs back`() {
        val list = state().copy(shelfIndex = 1, focusByShelf = mapOf(platformShelfKey("ps2") to 6))
        val grid = list.activate()
        assertEquals(PickerZone.GRID, grid.focusZone)
        assertEquals(list.selectedGameIds, grid.selectedGameIds)
        assertEquals(6, grid.focusedIndex())   // the shelf remembers where the cursor was
        assertEquals(PickerZone.RAIL, grid.back()?.focusZone)
    }

    @Test
    fun `B on the list has no level left and closes`() {
        assertNull(state().back())
    }

    @Test
    fun `stepping shelves keeps each shelf's own cursor`() {
        val s = onPs2().copy(focusZone = PickerZone.RAIL, focusByShelf = mapOf(platformShelfKey("ps2") to 4))
        val snes = s.press(GamepadAction.NAVIGATE_DOWN)
        assertEquals(2, snes.shelfIndex)
        assertEquals(0, snes.focusedIndex())
        assertEquals(4, snes.press(GamepadAction.NAVIGATE_UP).focusedIndex())
        assertEquals(0, state().press(GamepadAction.NAVIGATE_UP).shelfIndex)
        assertEquals(s.shelves.lastIndex, s.press(*Array(9) { GamepadAction.NAVIGATE_DOWN }).shelfIndex)
    }

    @Test
    fun `B from the grid returns to the same shelf, and A goes back to the tile it left`() {
        val grid = onPs2().copy(focusByShelf = mapOf(platformShelfKey("ps2") to 3))
        val list = grid.back()!!
        assertEquals(1, list.shelfIndex)
        assertEquals(3, list.activate().focusedIndex())
    }

    @Test
    fun `shoulder buttons do nothing`() {
        val s = onPs2()
        assertEquals(s, s.move(GamepadAction.PREV_CATEGORY))
        assertEquals(s, s.move(GamepadAction.NEXT_CATEGORY))
    }

    // ── Grid navigation ──────────────────────────────────────────────────────

    @Test
    fun `grid moves step within a row and never wrap or leave the grid`() {
        val s = onPs2()
        assertEquals(1, s.press(GamepadAction.NAVIGATE_RIGHT).focusedIndex())
        assertEquals(6, s.press(GamepadAction.NAVIGATE_DOWN).focusedIndex())
        // Left on column one stays in the grid: only B climbs to the list.
        val left = s.press(GamepadAction.NAVIGATE_LEFT)
        assertEquals(PickerZone.GRID, left.focusZone)
        assertEquals(0, left.focusedIndex())
        val rowEnd = s.copy(focusByShelf = mapOf(platformShelfKey("ps2") to 5))
        assertEquals(5, rowEnd.press(GamepadAction.NAVIGATE_RIGHT).focusedIndex())
        // Down from past the end of the shorter row below lands on its nearest tile, the last.
        assertEquals(7, rowEnd.press(GamepadAction.NAVIGATE_DOWN).focusedIndex())
        val lastRow = s.copy(focusByShelf = mapOf(platformShelfKey("ps2") to 7))
        assertEquals(7, lastRow.press(GamepadAction.NAVIGATE_DOWN).focusedIndex())
        assertEquals(0, s.press(GamepadAction.NAVIGATE_UP).focusedIndex())
    }

    // ── Shelf layout: XMB-sized tiles in free-flowing rows ───────────────────

    private val boxHeight = PICKER_ART_HEIGHT_DP
    private val pad = PICKER_FRAME_PAD_DP * 2

    @Test
    fun `ICON0 tiles take the XMB game row size on every console`() {
        val icon0 = PICKER_ICON0_WIDTH_DP + pad
        assertEquals(icon0, pickerTileWidthDp("snes", IconDisplayMode.ICON0), 0.01f)
        assertEquals(icon0, pickerTileWidthDp("psp", IconDisplayMode.ICON0), 0.01f)
        assertEquals(126f, PICKER_ICON0_WIDTH_DP)
        assertEquals(70f, PICKER_ICON0_HEIGHT_DP)
    }

    @Test
    fun `box tiles are as wide as the console's box at the XMB art height`() {
        assertEquals(boxHeight * 600f / 438f + pad, pickerTileWidthDp("snes", IconDisplayMode.BOX_ART), 0.01f)
        assertEquals(boxHeight * 430f / 600f + pad, pickerTileWidthDp("ps2", IconDisplayMode.BOX_3D), 0.01f)
        assertEquals(boxHeight + pad, pickerTileWidthDp("snes", IconDisplayMode.PHYSICAL_MEDIA), 0.01f)
        // A UMD case is narrower than its two-line label needs: the tile keeps a floor.
        assertEquals(PICKER_MIN_TILE_WIDTH_DP, pickerTileWidthDp("psp", IconDisplayMode.BOX_ART), 0.01f)
    }

    // Packing and nearest-tile movement themselves are the core's FlowGrid (FlowGridTest); these pin
    // the picker's widths and pane feeding it.

    @Test
    fun `ICON0 rows hold as many tiles as the pane fits`() {
        assertEquals(listOf(0..5, 6..7), onPs2().shelfRows())
        assertEquals(listOf(0..4, 5..7), onPs2().copy(shelfWidthDp = sixIcon0Wide - 1f).shelfRows())
    }

    @Test
    fun `a mixed shelf flows each box at its own width`() {
        val mixed = GameShelf("mixed", "Mixed", listOf(game(1, "snes"), game(2, "psp"), game(3, "psp"), game(4, "snes"), game(5, "psp")))
        val s = GamePickerState(shelves = listOf(mixed), focusZone = PickerZone.GRID, viewMode = IconDisplayMode.BOX_ART, shelfWidthDp = 300f)
        // SNES ≈ 123 + PSP 64 + PSP 64 (+ gaps) fits 300; the next SNES spills.
        assertEquals(listOf(0..2, 3..4), s.shelfRows())
    }

    @Test
    fun `on a mixed shelf up and down land on the tile nearest the cursor's centre`() {
        val mixed = GameShelf("mixed", "Mixed", listOf(game(1, "snes"), game(2, "psp"), game(3, "psp"), game(4, "snes"), game(5, "psp")))
        val s = GamePickerState(shelves = listOf(mixed), focusZone = PickerZone.GRID, viewMode = IconDisplayMode.BOX_ART, shelfWidthDp = 300f)
        // Rows: SNES PSP PSP / SNES PSP. From the second PSP (past the shorter row's end) down is the last PSP.
        val at2 = s.copy(focusByShelf = mapOf("mixed" to 2))
        assertEquals(4, at2.press(GamepadAction.NAVIGATE_DOWN).focusedIndex())
        assertEquals(3, s.press(GamepadAction.NAVIGATE_DOWN).focusedIndex())
        assertEquals(1, s.copy(focusByShelf = mapOf("mixed" to 4)).press(GamepadAction.NAVIGATE_UP).focusedIndex())
    }

    @Test
    fun `custom card tiles take the ICON0 width`() {
        val cardsShelf = state().let { it.copy(shelfIndex = it.shelves.lastIndex, shelfWidthDp = sixIcon0Wide, viewMode = IconDisplayMode.BOX_ART) }
        assertEquals(List(2) { PICKER_ICON0_WIDTH_DP + pad }, cardsShelf.currentShelf()!!.tileWidthsDp(cardsShelf.viewMode))
    }

    // ── Selection ────────────────────────────────────────────────────────────

    @Test
    fun `A toggles the focused game`() {
        val s = onPs2().copy(focusByShelf = mapOf(platformShelfKey("ps2") to 2))
        assertTrue(3L in s.activate().selectedGameIds)
        assertFalse(3L in s.activate().activate().selectedGameIds)
    }

    @Test
    fun `a game on two shelves shares one checkmark`() {
        // Game 1 is on "In RPGs" (index 0) and on the PS2 shelf (index 0).
        val unchecked = state().toggleAt(0)
        assertFalse(1L in unchecked.selectedGameIds)
        val ps2 = unchecked.copy(shelfIndex = 1)
        assertTrue(1L in ps2.toggleAt(0).selectedGameIds)
    }

    @Test
    fun `whole shelf checks a partial shelf and then unchecks a full one`() {
        val s = onPs2()
        val all = s.toggleWholeShelf()
        assertTrue(all.selectedGameIds.containsAll((1L..8L).toList()))
        assertTrue(9L in all.selectedGameIds)   // other shelves are untouched
        val none = all.toggleWholeShelf()
        assertTrue((1L..8L).none { it in none.selectedGameIds })
        assertTrue(9L in none.selectedGameIds)
    }

    @Test
    fun `custom cards toggle their own set`() {
        val cardsShelf = state().let { it.copy(shelfIndex = it.shelves.lastIndex) }
        val picked = cardsShelf.toggleAt(1)
        assertEquals(setOf(501L), picked.selectedCollectionIds)
        assertEquals(cardsShelf.selectedGameIds, picked.selectedGameIds)
    }

    @Test
    fun `counts match what confirm will do`() {
        // Preselected {1, 9}: uncheck 9, check 2 and 3, move one card in.
        val s = state()
            .copy(shelfIndex = 2).toggleAt(0)                     // SNES game 9 off
            .copy(shelfIndex = 1).toggleAt(1).toggleAt(2)         // PS2 games 2, 3 on
            .let { it.copy(shelfIndex = it.shelves.lastIndex) }.toggleAt(0)
        assertEquals(setOf(9L), s.pendingRemovals())
        assertEquals(3, s.pendingAddCount())
        assertEquals(1, s.checkedCount(s.shelves.first { it.key == IN_CATEGORY_SHELF_KEY }))
        assertEquals(3, s.checkedCount(s.shelves.first { it.key == platformShelfKey("ps2") }))
    }

    // ── Robustness ───────────────────────────────────────────────────────────

    @Test
    fun `a shelf shrinking under the cursor re-clamps it`() {
        val s = onPs2().copy(focusByShelf = mapOf(platformShelfKey("ps2") to 7, "gone" to 3))
        val fewer = buildShelves(cards, games.filter { it.id <= 3 || it.id >= 9 }, collections, setOf(1, 9), null, "RPGs")
        val clamped = s.copy(shelves = fewer).clampFocus()
        assertEquals(2, clamped.focusedIndex())
        assertNull(clamped.focusByShelf["gone"])
    }

    @Test
    fun `losing shelves clamps the shelf index`() {
        val s = state().let { it.copy(shelfIndex = it.shelves.lastIndex) }
        val clamped = s.copy(shelves = s.shelves.take(2)).clampFocus()
        assertEquals(1, clamped.shelfIndex)
    }

    @Test
    fun `a grid cursor on a shelf that emptied goes back to the list`() {
        val s = onPs2()
        val emptied = s.copy(shelves = s.shelves.map { if (it is GameShelf && it.key == platformShelfKey("ps2")) it.copy(games = emptyList()) else it })
        assertEquals(PickerZone.RAIL, emptied.clampFocus().focusZone)
    }

    @Test
    fun `empty picker ignores every input`() {
        val empty = GamePickerState()
        assertEquals(empty, empty.press(GamepadAction.NAVIGATE_DOWN, GamepadAction.NAVIGATE_LEFT))
        assertEquals(empty, empty.activate())
        assertEquals(empty, empty.toggleWholeShelf())
        assertEquals(0, empty.focusedIndex())
    }

    // ── Touch and view ───────────────────────────────────────────────────────

    @Test
    fun `a tap toggles and hides the cursor until the next controller input`() {
        val tapped = onPs2().tapTile(3)
        assertTrue(4L in tapped.selectedGameIds)
        assertTrue(tapped.usingTouch)
        val revived = tapped.press(GamepadAction.NAVIGATE_RIGHT)
        assertFalse(revived.usingTouch)
        assertEquals(3, revived.focusedIndex())   // the first press only brings the cursor back
    }

    @Test
    fun `each input that changes something sounds once`() {
        val grid = onPs2()
        val list = state()
        assertEquals(MenuSound.SCROLL, gamePickerSound(grid, grid.press(GamepadAction.NAVIGATE_RIGHT)))
        assertEquals(MenuSound.SYSTEM_BROWSE, gamePickerSound(list, list.press(GamepadAction.NAVIGATE_DOWN)))
        assertEquals(MenuSound.SELECT, gamePickerSound(list.copy(shelfIndex = 1), list.copy(shelfIndex = 1).activate()))
        assertEquals(MenuSound.BACK, gamePickerSound(grid, grid.back()!!))
        assertEquals(MenuSound.SELECT, gamePickerSound(grid, grid.activate()))
        assertEquals(MenuSound.SELECT, gamePickerSound(grid, grid.toggleWholeShelf()))
        assertEquals(MenuSound.SELECT, gamePickerSound(grid, grid.copy(viewMode = IconDisplayMode.BOX_ART)))
    }

    @Test
    fun `inputs that change nothing are silent`() {
        val grid = onPs2()
        assertNull(gamePickerSound(grid, grid.press(GamepadAction.NAVIGATE_UP)))
        assertNull(gamePickerSound(state(), state().press(GamepadAction.NAVIGATE_UP)))
        val touched = grid.copy(usingTouch = true)
        assertNull(gamePickerSound(touched, touched.press(GamepadAction.NAVIGATE_RIGHT)))
    }

    // ── Keeping the cursor on screen ─────────────────────────────────────────

    @Test
    fun `a fully visible item needs no scroll`() {
        assertEquals(0, scrollIntoViewDelta(itemStart = 100, itemEnd = 160, viewStart = 0, viewEnd = 800))
    }

    @Test
    fun `an item cut off at the top scrolls up just enough - a sliver on screen is not on screen`() {
        // Game Boy Color, its bottom edge peeking out under the header.
        assertEquals(-50, scrollIntoViewDelta(itemStart = -50, itemEnd = 10, viewStart = 0, viewEnd = 800))
    }

    @Test
    fun `an item cut off at the bottom scrolls down just enough, not to the top`() {
        assertEquals(40, scrollIntoViewDelta(itemStart = 780, itemEnd = 840, viewStart = 0, viewEnd = 800))
    }

    @Test
    fun `an item taller than the view lines its top up`() {
        assertEquals(20, scrollIntoViewDelta(itemStart = 20, itemEnd = 1200, viewStart = 0, viewEnd = 800))
    }

    // ── The header's pending-change label ────────────────────────────────────

    @Test
    fun `the change label names only the parts that are not zero`() {
        assertEquals("No changes", pendingChangeLabel(adds = 0, removals = 0))
        assertEquals("2 to add", pendingChangeLabel(adds = 2, removals = 0))
        assertEquals("1 to remove", pendingChangeLabel(adds = 0, removals = 1))
        assertEquals("2 to add · 1 to remove", pendingChangeLabel(adds = 2, removals = 1))
    }

    // ── Y: the options menu (View ›, Select All) ────────────────────────────

    @Test
    fun `the menu's root offers View with the current mode and Select All`() {
        val s = onPs2().openMenu()
        val rows = s.menuRows()
        assertEquals(listOf("View", "Select All"), rows.map { it.label })
        assertEquals(IconDisplayMode.ICON0.label, rows[0].value)
        assertTrue(rows[0].opensMenu)
        assertEquals("Off", rows[1].value)
    }

    @Test
    fun `View lists every mode with the current one checked, and picking one applies it and closes`() {
        val view = onPs2().openMenu().activateMenuRow()
        assertEquals(PickerMenuLevel.VIEW, view.menu?.level)
        assertEquals(IconDisplayMode.entries.map { it.label }, view.menuRows().map { it.label })
        assertEquals(listOf(true) + List(IconDisplayMode.entries.size - 1) { false }, view.menuRows().map { it.checked })
        val picked = view.copy(menu = view.menu!!.copy(selectedIndex = IconDisplayMode.BOX_ART.ordinal)).activateMenuRow()
        assertEquals(IconDisplayMode.BOX_ART, picked.viewMode)
        assertNull(picked.menu)
    }

    @Test
    fun `Select All checks the shelf, reads On once it is all checked, and unchecks it again`() {
        val atSelectAll = onPs2().openMenu().let { it.copy(menu = it.menu!!.copy(selectedIndex = 1)) }
        val all = atSelectAll.activateMenuRow()
        assertTrue(all.selectedGameIds.containsAll((1L..8L).toList()))
        assertNull(all.menu)
        assertEquals("On", all.openMenu().menuRows()[1].value)
        val none = all.openMenu().let { it.copy(menu = it.menu!!.copy(selectedIndex = 1)) }.activateMenuRow()
        assertFalse(none.selectedGameIds.any { it in 1L..8L })
    }

    @Test
    fun `back from View climbs to the root on View, and from the root closes`() {
        val view = onPs2().openMenu().activateMenuRow()
        val root = view.menuUp()
        assertEquals(PickerMenuLevel.ROOT, root.menu?.level)
        assertEquals(0, root.menu?.selectedIndex)
        assertNull(root.closeMenu().menu)
    }

    // ── X: search the current shelf ──────────────────────────────────────────

    @Test
    fun `a query narrows the current shelf to matching titles, any case`() {
        val s = onPs2().copy(searchActive = true, query = "GAME 1")
        // "Game 1" only: "Game 10" etc. do not exist on the 8-game PS2 shelf.
        assertEquals(listOf(1L), (s.currentShelf() as GameShelf).games.map { it.id })
        // The shelf list still counts the whole shelf.
        assertEquals(8, s.shelves[1].size)
    }

    @Test
    fun `a blank or closed search shows the whole shelf`() {
        assertEquals(8, onPs2().copy(searchActive = true, query = "  ").currentShelf()?.size)
        assertEquals(8, onPs2().copy(searchActive = false, query = "zzz").currentShelf()?.size)
    }

    @Test
    fun `X opens a closed search, brings the keyboard back over text, and closes an empty one`() {
        val opened = onPs2().pressSearch()
        assertTrue(opened.searchActive)
        val reopened = opened.copy(query = "ga").pressSearch()
        assertTrue(reopened.searchActive)
        assertEquals("ga", reopened.query)
        assertEquals(opened.searchReopens + 1, reopened.searchReopens)
        val closed = opened.pressSearch()
        assertFalse(closed.searchActive)
    }

    @Test
    fun `stepping to another shelf ends the search - it belongs to the shelf it was made on`() {
        val searching = onPs2().copy(focusZone = PickerZone.RAIL, searchActive = true, query = "game")
        val next = searching.press(GamepadAction.NAVIGATE_DOWN)
        assertFalse(next.searchActive)
        assertEquals("", next.query)
    }

    @Test
    fun `moving in the grid steps over the filtered tiles`() {
        val s = onPs2().copy(searchActive = true, query = "game 2")
        assertEquals(listOf(2L), (s.currentShelf() as GameShelf).games.map { it.id })
        assertEquals(0, s.press(GamepadAction.NAVIGATE_RIGHT).focusedIndex())
        assertTrue(2L in s.activate().selectedGameIds)
    }
}
