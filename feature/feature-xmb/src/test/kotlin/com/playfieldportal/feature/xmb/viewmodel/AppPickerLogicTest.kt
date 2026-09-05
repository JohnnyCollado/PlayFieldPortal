package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.GamepadAction
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
}
