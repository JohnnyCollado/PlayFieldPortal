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
 * the list, grid moves without wrapping, selection keyed by id rather than by tile, whole-shelf
 * toggling, the single picker-wide view, add/remove counts that match
 * XMBViewModel.confirmGamePicker, and the menu sound for each input.
 */
class GamePickerLogicTest {

    private fun game(id: Long, platform: String, type: GameContentType = GameContentType.GAME) =
        Game(id = id, title = "Game $id", platformId = platform, contentType = type)

    private fun card(platform: String, name: String = platform.uppercase()) =
        MemoryCard(platformId = platform, displayName = name)

    private fun collection(id: Long) = GameCollection(id = id, name = "Card $id")

    // 8 PS2 games (two rows: 6 + 2), 3 SNES games, one Android app on PS2's card that must never
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

    /** The PS2 shelf, cursor in its grid. */
    private fun onPs2() = state().copy(shelfIndex = 1, focusZone = PickerZone.GRID)

    private fun GamePickerState.press(vararg actions: GamepadAction) =
        actions.fold(this) { s, a -> s.move(a) }

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
        val s = onPs2().copy(focusByShelf = mapOf(platformShelfKey("ps2") to 4))
        val snes = s.stepShelf(+1)
        assertEquals(2, snes.shelfIndex)
        assertEquals(0, snes.focusedIndex())
        assertEquals(4, snes.stepShelf(-1).focusedIndex())
        assertEquals(0, state().stepShelf(-1).shelfIndex)
        assertEquals(s.shelves.lastIndex, s.stepShelf(+99).shelfIndex)
    }

    @Test
    fun `shoulder buttons do nothing`() {
        val s = onPs2()
        assertEquals(s, s.move(GamepadAction.PREV_CATEGORY))
        assertEquals(s, s.move(GamepadAction.NEXT_CATEGORY))
    }

    // ── Grid navigation ──────────────────────────────────────────────────────

    @Test
    fun `grid moves follow gridMove and never wrap or leave the grid`() {
        val s = onPs2()
        assertEquals(1, s.press(GamepadAction.NAVIGATE_RIGHT).focusedIndex())
        assertEquals(6, s.press(GamepadAction.NAVIGATE_DOWN).focusedIndex())
        // Left on column one stays in the grid: only B climbs to the list.
        val left = s.press(GamepadAction.NAVIGATE_LEFT)
        assertEquals(PickerZone.GRID, left.focusZone)
        assertEquals(0, left.focusedIndex())
        val rowEnd = s.copy(focusByShelf = mapOf(platformShelfKey("ps2") to 5))
        assertEquals(5, rowEnd.press(GamepadAction.NAVIGATE_RIGHT).focusedIndex())
        assertEquals(5, rowEnd.press(GamepadAction.NAVIGATE_DOWN).focusedIndex())
        assertEquals(0, s.press(GamepadAction.NAVIGATE_UP).focusedIndex())
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
        assertEquals(empty, empty.stepShelf(1))
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
    fun `one view covers every tile and X steps through every mode then wraps`() {
        var s = state().copy(viewMode = IconDisplayMode.PHYSICAL_MEDIA)
        val seen = mutableListOf<IconDisplayMode>()
        repeat(IconDisplayMode.entries.size) { s = s.cycleView(); seen += s.viewMode }
        assertEquals(IconDisplayMode.entries.size, seen.toSet().size)
        assertEquals(IconDisplayMode.PHYSICAL_MEDIA, seen.last())
    }

    // ── Navigation sounds ────────────────────────────────────────────────────

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
        assertEquals(MenuSound.SELECT, gamePickerSound(grid, grid.cycleView()))
    }

    @Test
    fun `inputs that change nothing are silent`() {
        val grid = onPs2()
        assertNull(gamePickerSound(grid, grid.press(GamepadAction.NAVIGATE_UP)))
        assertNull(gamePickerSound(state(), state().press(GamepadAction.NAVIGATE_UP)))
        val touched = grid.copy(usingTouch = true)
        assertNull(gamePickerSound(touched, touched.press(GamepadAction.NAVIGATE_RIGHT)))
    }
}
