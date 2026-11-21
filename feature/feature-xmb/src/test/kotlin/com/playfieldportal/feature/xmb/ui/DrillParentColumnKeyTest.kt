package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.feature.xmb.viewmodel.XMBItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The flyout's left column shows the parent list with the opened row on the arrow. Stepping into a
 * deeper level can swap that column for a different list (Social: the account, then the hub's rows).
 * A different list must be drawn in place at once; only moving along the SAME list may glide.
 * Reported 21 Nov 2026: entering a hub row that is not the first one showed the new list at its top
 * and then scrolled it down to the opened row.
 */
class DrillParentColumnKeyTest {

    private fun rows(vararg ids: String) = ids.map { XMBItem(id = it, title = it) }

    @Test
    fun `a different parent list is a different column`() {
        val account = rows("social_account")
        val hub = rows("friends", "voice", "activity", "discord")
        assertNotEquals(drillParentColumnKey(account), drillParentColumnKey(hub))
    }

    @Test
    fun `the same parent list is the same column whichever row is opened`() {
        // Voice and its own deeper levels all sit under the same hub rows.
        assertEquals(
            drillParentColumnKey(rows("friends", "voice", "activity")),
            drillParentColumnKey(rows("friends", "voice", "activity")),
        )
    }

    @Test
    fun `a row whose text changed is still the same column`() {
        val before = listOf(XMBItem(id = "friends", title = "Friends", subtitle = "2 online"))
        val after = listOf(XMBItem(id = "friends", title = "Friends", subtitle = "3 online"))
        assertEquals(drillParentColumnKey(before), drillParentColumnKey(after))
    }
}
