package com.playfieldportal.feature.settings.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControllerNavigationStateTest {

    private fun item(
        key: String,
        focusable: Boolean = true,
        selectable: Boolean = true,
        enabled: Boolean = true,
        onSelect: (() -> Unit)? = null,
    ) = ControllerNavItem(
        key = key,
        focusable = focusable,
        selectable = selectable,
        enabled = enabled,
        onSelect = onSelect,
    )

    @Test
    fun `empty list has no focus, no movement and no selection`() {
        val state = ControllerNavigationState()
        assertNull(state.focusedKey)
        assertNull(state.move(1))
        assertNull(state.move(-1))
        assertNull(state.focusFirst())
        assertFalse(state.select())
    }

    @Test
    fun `updateItems focuses the first navigable item`() {
        val state = ControllerNavigationState()
        state.updateItems(listOf(item("a"), item("b")))
        assertEquals("a", state.focusedKey)
    }

    @Test
    fun `up and down movement traverse the list in order`() {
        val state = ControllerNavigationState()
        state.updateItems(listOf(item("a"), item("b"), item("c")))
        assertEquals("b", state.move(1))
        assertEquals("c", state.move(1))
        assertEquals("b", state.move(-1))
        assertEquals("a", state.move(-1))
    }

    @Test
    fun `movement clamps at both boundaries`() {
        val state = ControllerNavigationState()
        state.updateItems(listOf(item("a"), item("b")))
        // Already at the first item — Up stays put.
        assertEquals("a", state.move(-1))
        assertEquals("b", state.move(1))
        // Already at the last item — Down stays put.
        assertEquals("b", state.move(1))
    }

    @Test
    fun `navigation skips section headers`() {
        val state = ControllerNavigationState()
        // Section headers are non-focusable landmarks: the cursor never lands on them and
        // movement flows straight between the rows around them.
        state.updateItems(listOf(item("hdr", focusable = false), item("a"), item("b")))
        assertEquals("a", state.focusedKey)
        assertEquals("b", state.move(1))
        assertEquals("a", state.move(-1))
        // Clamped at the first row — the header is not a navigation target.
        assertEquals("a", state.move(-1))
    }

    @Test
    fun `select dispatches to the focused item action`() {
        val state = ControllerNavigationState()
        var selected: String? = null
        state.updateItems(
            listOf(
                item("a", onSelect = { selected = "a" }),
                item("b", onSelect = { selected = "b" }),
            ),
        )
        assertTrue(state.select())
        assertEquals("a", selected)
        state.move(1)
        assertTrue(state.select())
        assertEquals("b", selected)
    }

    @Test
    fun `select ignores non-selectable read-only rows`() {
        val state = ControllerNavigationState()
        var selected = false
        state.updateItems(listOf(item("ro", selectable = false, onSelect = { selected = true })))
        assertFalse(state.select())
        assertFalse(selected)
    }

    @Test
    fun `movement skips disabled items`() {
        val state = ControllerNavigationState()
        state.updateItems(
            listOf(
                item("a"),
                item("disabled", enabled = false),
                item("b"),
            ),
        )
        assertEquals("b", state.move(1))
        assertEquals("a", state.move(-1))
    }

    @Test
    fun `select is a no-op on a disabled item`() {
        val state = ControllerNavigationState()
        var selected = false
        state.updateItems(listOf(item("a"), item("disabled", enabled = false, onSelect = { selected = true })))
        state.setFocused("disabled")
        assertFalse(state.select())
        assertFalse(selected)
    }

    @Test
    fun `stable key preserves focus across list updates`() {
        val state = ControllerNavigationState()
        state.updateItems(listOf(item("a"), item("b"), item("c")))
        state.move(1)
        state.updateItems(listOf(item("a"), item("b"), item("c"), item("d")))
        assertEquals("b", state.focusedKey)
        state.updateItems(listOf(item("x"), item("b"), item("c")))
        assertEquals("b", state.focusedKey)
    }

    @Test
    fun `removing the focused item recovers to the nearest survivor by order`() {
        val state = ControllerNavigationState()
        state.updateItems(listOf(item("a"), item("b"), item("c"), item("d")))
        state.move(2)
        state.updateItems(listOf(item("a"), item("b"), item("d")))
        assertEquals("d", state.focusedKey)
    }

    @Test
    fun `removing the focused first item falls back to the first survivor`() {
        val state = ControllerNavigationState()
        state.updateItems(listOf(item("a"), item("b"), item("c")))
        state.updateItems(listOf(item("b"), item("c")))
        assertEquals("b", state.focusedKey)
    }

    @Test
    fun `focusFirst returns the first navigable key`() {
        val state = ControllerNavigationState()
        state.updateItems(listOf(item("a"), item("b")))
        state.move(1)
        assertEquals("a", state.focusFirst())
    }

    @Test
    fun `setFocused aligns the focused key and ignores unknown keys`() {
        val state = ControllerNavigationState()
        state.updateItems(listOf(item("a"), item("b")))
        state.setFocused("b")
        assertEquals("b", state.focusedKey)
        state.setFocused("nope")
        assertEquals("b", state.focusedKey)
        state.setFocused(null)
        assertNull(state.focusedKey)
    }

    @Test
    fun `select invokes the updated callback after an item changes`() {
        val state = ControllerNavigationState()
        var selected = ""
        state.updateItems(listOf(item("a", onSelect = { selected = "first" })))
        state.updateItems(listOf(item("a", onSelect = { selected = "second" })))
        assertTrue(state.select())
        assertEquals("second", selected)
    }

    @Test
    fun `horizontal movement enters a row's inline actions and returns to it`() {
        val state = ControllerNavigationState()
        var actionSelected = false
        val row = item("row", onSelect = {}).copy(
            trailingActions = listOf(
                ControllerNavItem(key = "row:a", selectable = true, onSelect = { actionSelected = true }),
                ControllerNavItem(key = "row:b", selectable = true),
            ),
        )
        state.updateItems(listOf(row))
        assertEquals("row", state.focusedKey)
        // RIGHT enters the first action.
        assertEquals("row:a", state.moveHorizontal(1))
        // RIGHT steps to the next action, clamped at the last.
        assertEquals("row:b", state.moveHorizontal(1))
        assertEquals("row:b", state.moveHorizontal(1))
        // LEFT walks back, then past the first action returns to the row.
        assertEquals("row:a", state.moveHorizontal(-1))
        assertEquals("row", state.moveHorizontal(-1))
        // LEFT on the row stays put.
        assertEquals("row", state.moveHorizontal(-1))
        // SELECT dispatches the focused inline action.
        assertEquals("row:a", state.moveHorizontal(1))
        assertTrue(state.select())
        assertTrue(actionSelected)
    }

    @Test
    fun `horizontal movement is a no-op on rows without actions`() {
        val state = ControllerNavigationState()
        state.updateItems(listOf(item("a"), item("b")))
        assertNull(state.moveHorizontal(1))
        assertNull(state.moveHorizontal(-1))
    }

    @Test
    fun `vertical movement exits an inline action to its row first`() {
        val state = ControllerNavigationState()
        state.updateItems(
            listOf(
                item("top"),
                item("row").copy(trailingActions = listOf(ControllerNavItem(key = "row:a"))),
                item("bottom"),
            ),
        )
        state.setFocused("row:a")
        assertEquals("bottom", state.move(1))
        state.setFocused("row:a")
        assertEquals("top", state.move(-1))
    }

    @Test
    fun `inline action focus survives a list update that keeps its row`() {
        val state = ControllerNavigationState()
        val row = item("row").copy(trailingActions = listOf(ControllerNavItem(key = "row:a")))
        state.updateItems(listOf(row))
        state.setFocused("row:a")
        state.updateItems(listOf(row))
        assertEquals("row:a", state.focusedKey)
    }

    @Test
    fun `inline action focus recovers to its row when the action disappears`() {
        val state = ControllerNavigationState()
        state.updateItems(listOf(item("row").copy(trailingActions = listOf(ControllerNavItem(key = "row:a")))))
        state.setFocused("row:a")
        state.updateItems(listOf(item("row")))
        assertEquals("row", state.focusedKey)
    }
}
