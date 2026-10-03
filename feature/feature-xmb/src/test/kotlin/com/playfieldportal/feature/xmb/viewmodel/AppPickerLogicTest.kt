package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.sound.MenuSound
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pure app-picker logic ([visibleApps], [AppPickerState.toggle], [AppPickerState.move],
 * [clampFocus], [pendingAdds], [pendingRemovals]) — the rules the
 * redesign demands: selection survives search, focus never strands, moves never wrap, and Apply
 * diffs against the membership the picker opened with.
 */
class AppPickerLogicTest {

    private fun app(pkg: String, label: String = pkg) = AppPickerEntry(packageName = pkg, label = label)

    // The cursor runs on the unified navigation engine through AppPickerNav, as XMBViewModel drives it.
    private val nav = AppPickerNav()

    private fun AppPickerState.move(action: GamepadAction) = nav.move(this, action)
    private fun AppPickerState.moveConfirm(action: GamepadAction) = nav.move(this, action)

    private fun state(
        packages: List<String> = listOf("a", "b", "c", "d", "e", "f", "g", "h"),
        selected: Set<String> = emptySet(),
        initialSelected: Set<String> = emptySet(),
        focusedIndex: Int = 0,
        query: String = "",
    ) = AppPickerState(
        title = "Add Apps",
        target = AppPickerTarget.AndroidGames("android"),
        apps = packages.map { app(it) },
        selected = selected,
        initialSelected = initialSelected,
        focusedIndex = focusedIndex,
        query = query,
    )

    // ── X: the controller's search button ─────────────────────────────────────────

    @Test
    fun `X opens a closed search`() {
        val opened = state().pressSearch()
        assertTrue(opened.searchActive)
        assertEquals(0, opened.searchReopens)
    }

    @Test
    fun `X on an open search with text brings the keyboard back and keeps the text`() {
        // PFP's keyboard Done leaves the search open with the keyboard down; X must not wipe it.
        val open = state(query = "dolph").copy(searchActive = true)

        val pressed = open.pressSearch()

        assertTrue(pressed.searchActive)
        assertEquals("dolph", pressed.query)
        assertEquals(1, pressed.searchReopens)
    }

    @Test
    fun `X on an open empty search closes it`() {
        val pressed = state().copy(searchActive = true).pressSearch()

        assertEquals(false, pressed.searchActive)
        assertEquals("", pressed.query)
    }

    // ── visibleApps / search ──────────────────────────────────────────────────────

    @Test
    fun `no query shows every app`() {
        assertEquals(8, state().visibleApps().size)
    }

    @Test
    fun `query filters by label case-insensitively`() {
        val s = state(
            packages = listOf("alpha", "Beta", "gamma"),
            query = "BET",
        )
        assertEquals(listOf("Beta"), s.visibleApps().map { it.packageName })
    }

    @Test
    fun `search never touches selected`() {
        val s = state(
            packages = listOf("a", "b"),
            selected = setOf("a", "b"),
            query = "b",
        )
        assertEquals(setOf("b"), s.visibleApps().map { it.packageName }.toSet())
        assertEquals(setOf("a", "b"), s.selected)
    }

    // ── toggle ────────────────────────────────────────────────────────────────────

    @Test
    fun `toggle adds then removes the same package`() {
        val afterAdd = state().toggle("a")
        assertEquals(setOf("a"), afterAdd.selected)
        val afterRemove = afterAdd.toggle("a")
        assertEquals(emptySet<String>(), afterRemove.selected)
    }

    @Test
    fun `toggle does not move focus and does not filter apps`() {
        val s = state(focusedIndex = 5).toggle("c")
        assertEquals(5, s.focusedIndex)
        assertEquals(8, s.apps.size)
    }

    @Test
    fun `toggle on an unknown package is a no-op`() {
        val s = state(selected = setOf("a"))
        val toggled = s.toggle("zzz")
        assertEquals(setOf("a"), toggled.selected)
    }

    // ── pending diffs ─────────────────────────────────────────────────────────────

    @Test
    fun `pendingAdds is selected minus initialSelected`() {
        val s = state(selected = setOf("a", "b"), initialSelected = setOf("b", "c"))
        assertEquals(setOf("a"), s.pendingAdds())
        assertEquals(setOf("c"), s.pendingRemovals())
    }

    @Test
    fun `toggled off then back on produces empty diffs`() {
        val s = state(selected = setOf("x"), initialSelected = setOf("x"))
        val roundTrip = s.toggle("x").toggle("x")
        assertTrue(roundTrip.pendingAdds().isEmpty())
        assertTrue(roundTrip.pendingRemovals().isEmpty())
    }

    @Test
    fun `selection surviving a search round-trip diffs clean against membership`() {
        // Open with membership, filter away a checked app, clear: nothing pending.
        val opened = state(selected = setOf("a", "d"), initialSelected = setOf("a", "d"))
        val filtered = opened.copy(query = "d")           // "a" hidden, still selected
        assertEquals(setOf("a", "d"), filtered.selected)
        val cleared = filtered.copy(query = "")
        assertTrue(cleared.pendingAdds().isEmpty())
        assertTrue(cleared.pendingRemovals().isEmpty())
    }

    // ── clampFocus ────────────────────────────────────────────────────────────────

    @Test
    fun `clampFocus on an empty visible list returns zero`() {
        val s = state(packages = emptyList(), focusedIndex = 3)
        assertEquals(0, s.clampFocus().focusedIndex)
    }

    @Test
    fun `clampFocus pulls a high index back when the list shrank`() {
        val shrunken = state(packages = listOf("a", "b"), focusedIndex = 7)
        assertEquals(1, shrunken.clampFocus().focusedIndex)
    }

    @Test
    fun `clampFocus after a filter removed the focused item stays in range`() {
        val filtered = state(packages = listOf("a", "b", "c", "d"), focusedIndex = 3, query = "a")
        assertEquals(0, filtered.clampFocus().focusedIndex)
    }

    @Test
    fun `clampFocus keeps a valid index unchanged`() {
        val s = state(focusedIndex = 4)
        assertEquals(4, s.clampFocus().focusedIndex)
    }

    // ── move (grid navigation, no wrap) ───────────────────────────────────────────

    private val sevenColumns = listOf(
        listOf("a", "b", "c", "d", "e", "f", "g"),
        listOf("h", "i", "j"),
    )   // 10 apps, 7 per row

    private fun gridState(focusedIndex: Int) = state(
        packages = sevenColumns.flatten(),
        focusedIndex = focusedIndex,
    )

    @Test
    fun `move right at the last column refuses to wrap to the next row`() {
        val next = gridState(6).move(GamepadAction.NAVIGATE_RIGHT)
        assertEquals(6, next.focusedIndex)
    }

    @Test
    fun `move left at column 0 refuses to wrap to the previous row`() {
        val next = gridState(7).move(GamepadAction.NAVIGATE_LEFT)
        assertEquals(7, next.focusedIndex)
    }

    @Test
    fun `move up past the first row is a no-op`() {
        val next = gridState(2).move(GamepadAction.NAVIGATE_UP)
        assertEquals(2, next.focusedIndex)
    }

    @Test
    fun `move down past the last row is a no-op`() {
        val next = gridState(9).move(GamepadAction.NAVIGATE_DOWN)
        assertEquals(9, next.focusedIndex)
    }

    @Test
    fun `move inside a row shifts by one`() {
        assertEquals(1, gridState(0).move(GamepadAction.NAVIGATE_RIGHT).focusedIndex)
        assertEquals(0, gridState(1).move(GamepadAction.NAVIGATE_LEFT).focusedIndex)
        assertEquals(8, gridState(1).move(GamepadAction.NAVIGATE_DOWN).focusedIndex)
        assertEquals(1, gridState(8).move(GamepadAction.NAVIGATE_UP).focusedIndex)
    }

    @Test
    fun `move down into the short last row stops at the last item`() {
        // From index 2 (row 0, col 2), down lands on index 9 — the last item of the 3-item row.
        assertEquals(9, gridState(2).move(GamepadAction.NAVIGATE_DOWN).focusedIndex)
    }

    @Test
    fun `down from past the end of the short last row lands on its nearest app`() {
        // Column 5 has nothing below it; the last app of the short row is the nearest.
        assertEquals(9, gridState(5).move(GamepadAction.NAVIGATE_DOWN).focusedIndex)
    }

    @Test
    fun `the first press after touch only brings the cursor back`() {
        val touched = gridState(3).copy(usingTouch = true)
        val revived = touched.move(GamepadAction.NAVIGATE_RIGHT)
        assertEquals(false, revived.usingTouch)
        assertEquals(3, revived.focusedIndex)
    }

    @Test
    fun `move on an empty list is a no-op`() {
        val s = state(packages = emptyList())
        assertEquals(0, s.move(GamepadAction.NAVIGATE_DOWN).focusedIndex)
    }

    // ── confirm-modal option focus ────────────────────────────────────────────────

    @Test
    fun `confirm modal starts focused on cancel`() {
        val s = state().copy(confirmingRemovals = true)
        assertEquals(AppPickerState.CONFIRM_CANCEL, s.confirmFocusedOption)
    }

    @Test
    fun `opening the confirm modal resets option focus to cancel`() {
        val s = state().copy(confirmingRemovals = true, confirmFocusedOption = AppPickerState.CONFIRM_REMOVE)
        assertEquals(AppPickerState.CONFIRM_CANCEL, s.openConfirm().confirmFocusedOption)
    }

    @Test
    fun `confirm modal left-right moves between cancel and remove without wrap`() {
        val s = state().copy(confirmingRemovals = true)
        assertEquals(AppPickerState.CONFIRM_REMOVE, s.moveConfirm(GamepadAction.NAVIGATE_RIGHT).confirmFocusedOption)
        assertEquals(AppPickerState.CONFIRM_CANCEL, s.moveConfirm(GamepadAction.NAVIGATE_LEFT).confirmFocusedOption)
    }

    @Test
    fun `confirm modal right at remove and left at cancel are no-ops`() {
        val s = state().copy(confirmingRemovals = true, confirmFocusedOption = AppPickerState.CONFIRM_REMOVE)
        assertEquals(AppPickerState.CONFIRM_REMOVE, s.moveConfirm(GamepadAction.NAVIGATE_RIGHT).confirmFocusedOption)
        assertEquals(AppPickerState.CONFIRM_CANCEL, s.moveConfirm(GamepadAction.NAVIGATE_LEFT).confirmFocusedOption)
    }

    @Test
    fun `confirm modal ignores up-down`() {
        val s = state().copy(confirmingRemovals = true)
        assertEquals(s, s.moveConfirm(GamepadAction.NAVIGATE_UP))
        assertEquals(s, s.moveConfirm(GamepadAction.NAVIGATE_DOWN))
    }

    @Test
    fun `moveConfirm is a no-op while the modal is closed`() {
        val s = state().moveConfirm(GamepadAction.NAVIGATE_RIGHT)
        assertEquals(false, s.confirmingRemovals)
        assertEquals(AppPickerState.CONFIRM_CANCEL, s.confirmFocusedOption)
    }

    @Test
    fun `cancelConfirm resets option focus to cancel`() {
        val s = state().copy(confirmingRemovals = true, confirmFocusedOption = AppPickerState.CONFIRM_REMOVE)
        val cancelled = s.cancelConfirm()
        assertEquals(false, cancelled.confirmingRemovals)
        assertEquals(AppPickerState.CONFIRM_CANCEL, cancelled.confirmFocusedOption)
    }

    // ── Navigation sounds ───────────────────────────────────────────────────────

    @Test
    fun `a cursor move plays the navigation sound`() {
        val s = state()
        assertEquals(MenuSound.SCROLL, appPickerSound(s, s.move(GamepadAction.NAVIGATE_RIGHT)))
    }

    @Test
    fun `a blocked move is silent`() {
        val s = state()
        assertEquals(null, appPickerSound(s, s.move(GamepadAction.NAVIGATE_LEFT)))
    }

    @Test
    fun `a toggle plays select`() {
        val s = state()
        assertEquals(MenuSound.SELECT, appPickerSound(s, s.toggle("a")))
    }

    @Test
    fun `stepping the removal modal plays the navigation sound`() {
        val s = state().openConfirm()
        assertEquals(MenuSound.SCROLL, appPickerSound(s, s.moveConfirm(GamepadAction.NAVIGATE_RIGHT)))
        assertEquals(null, appPickerSound(s, s.moveConfirm(GamepadAction.NAVIGATE_LEFT)))
    }

    @Test
    fun `no picker means no sound`() {
        assertEquals(null, appPickerSound(null, state()))
        assertEquals(null, appPickerSound(state(), null))
    }

    @Test
    fun `the removal question names what the apps leave - a card, or the library`() {
        assertEquals("Remove 2 app(s) from this card?", removalQuestion(AppPickerTarget.CardApps(7), 2))
        assertEquals("Remove 1 app(s) from this library?", removalQuestion(AppPickerTarget.CategoryShortcuts("network"), 1))
        assertEquals("Remove 3 app(s) from this library?", removalQuestion(AppPickerTarget.AndroidGames("android"), 3))
    }
}
