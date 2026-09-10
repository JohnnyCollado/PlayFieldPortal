package com.playfieldportal.feature.xmb.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A renamed game re-sorts its list. The cursor must follow the game, not stay on its old slot —
 * which is what made a rename look like it had not applied.
 */
class CursorAfterRefreshTest {

    private fun rows(vararg ids: String) = ids.map { XMBItem(id = it, title = it) }

    @Test
    fun `a renamed game that moves down the list keeps the cursor`() {
        // "crash" was renamed to something that sorts after "sonic": first row to last.
        val before = rows("crash", "mario", "sonic")
        val after = rows("mario", "sonic", "crash")

        assertEquals(2, cursorAfterRefresh(before, previousIndex = 0, next = after))
    }

    @Test
    fun `a refresh that changes nothing leaves the cursor where it was`() {
        val list = rows("crash", "mario", "sonic")

        assertEquals(1, cursorAfterRefresh(list, previousIndex = 1, next = list))
    }

    @Test
    fun `a row that is gone falls back to its old index, clamped`() {
        val before = rows("crash", "mario", "sonic")

        assertEquals(1, cursorAfterRefresh(before, previousIndex = 1, next = rows("crash", "sonic")))
        assertEquals(0, cursorAfterRefresh(before, previousIndex = 2, next = rows("crash")))
        assertEquals(0, cursorAfterRefresh(before, previousIndex = 2, next = emptyList()))
    }
}
