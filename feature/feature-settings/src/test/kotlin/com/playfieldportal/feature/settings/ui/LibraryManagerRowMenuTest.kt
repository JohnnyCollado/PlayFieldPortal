package com.playfieldportal.feature.settings.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings > Library Manager: an Android app row and a file-extension row no longer remove on a press.
 * Each opens a per-item menu. Removing an app drops a game from the library (data loss), so it is red
 * and the screen confirms it; an extension is re-addable, so it is plain and unconfirmed.
 */
class LibraryManagerRowMenuTest {

    private val calls = mutableListOf<String>()

    @Test
    fun `an app menu is one red Remove from Library row`() {
        val rows = libraryAppMenuRows(gameId = 9L) { calls += "ask:$it" }

        assertEquals(listOf("Remove from Library"), rows.map { it.label })
        assertTrue(rows.single().destructive)
    }

    @Test
    fun `building the app menu asks nothing, and the row hands the game to the confirm`() {
        val rows = libraryAppMenuRows(gameId = 9L) { calls += "ask:$it" }
        assertTrue(calls.isEmpty())

        rows.single().action()

        assertEquals(listOf("ask:9"), calls)
    }

    @Test
    fun `an extension menu is one plain Remove Extension row`() {
        val rows = libraryExtensionMenuRows("psp", "iso") { p, e -> calls += "remove:$p:$e" }

        assertEquals(listOf("Remove Extension"), rows.map { it.label })
        assertTrue(rows.none { it.destructive })
    }

    @Test
    fun `building the extension menu removes nothing, and the row removes that extension`() {
        val rows = libraryExtensionMenuRows("psp", "iso") { p, e -> calls += "remove:$p:$e" }
        assertTrue(calls.isEmpty())

        rows.single().action()

        assertEquals(listOf("remove:psp:iso"), calls)
    }
}
