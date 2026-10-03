package com.playfieldportal.feature.settings.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Settings > Custom Memory Cards: a game row on a card no longer removes the game when pressed.
 * Its per-item menu carries View Game Details and Remove from Card; Remove is reversible (the game
 * can be added back), so it is neither red nor confirmed.
 */
class CollectionCardGameMenuTest {

    private val calls = mutableListOf<String>()

    private fun rows() = collectionGameMenuRows(
        gameId = 7L,
        onViewDetails = { calls += "details:$it" },
        onRemove = { calls += "remove:$it" },
    )

    @Test
    fun `the menu is View Game Details then Remove from Card`() {
        assertEquals(listOf("View Game Details", "Remove from Card"), rows().map { it.label })
    }

    @Test
    fun `Remove from Card is reversible so no row is destructive`() {
        assertTrue(rows().none { it.destructive })
    }

    @Test
    fun `building the menu removes nothing, and Remove from Card removes that game`() {
        val built = rows()
        assertTrue(calls.isEmpty())

        built.last().action()

        assertEquals(listOf("remove:7"), calls)
    }

    @Test
    fun `View Game Details opens that game and does not remove it`() {
        rows().first().action()

        assertEquals(listOf("details:7"), calls)
    }

    @Test
    fun `the sublabel no longer promises tap to remove`() {
        val sublabel = collectionGameSublabel("psp")

        assertEquals("PSP", sublabel)
        assertFalse(sublabel.contains("remove", ignoreCase = true))
    }
}
