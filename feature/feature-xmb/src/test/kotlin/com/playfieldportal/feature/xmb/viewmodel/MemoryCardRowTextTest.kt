package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.MemoryCard
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A console Memory Card row in the Game column: the console is the title, and "Memory Card" moves
 * to the subtitle with the game count, the way a custom card reads "Custom · 12 Games".
 */
class MemoryCardRowTextTest {

    private fun card(platform: String, name: String, pinned: Boolean = false) =
        MemoryCard(platformId = platform, displayName = name, pinned = pinned)

    @Test
    fun `a default card name loses its Memory Card ending`() {
        assertEquals("PlayStation", memoryCardRowTitle(card("psx", "PlayStation Memory Card")))
        assertEquals("Windows", memoryCardRowTitle(card("windows", "Windows Memory Card")))
    }

    @Test
    fun `a card the user renamed keeps its name`() {
        assertEquals("Retro Shelf", memoryCardRowTitle(card("snes", "Retro Shelf")))
        // A name that is only the ending is not emptied.
        assertEquals("Memory Card", memoryCardRowTitle(card("x", "Memory Card")))
    }

    @Test
    fun `the subtitle names the card and counts its games`() {
        assertEquals("Memory Card · 24 Games", memoryCardRowSubtitle(count = 24, pinned = false))
        assertEquals("Memory Card · 1 Game", memoryCardRowSubtitle(count = 1, pinned = false))
        assertEquals("Memory Card · Pinned · 31 Games", memoryCardRowSubtitle(count = 31, pinned = true))
    }
}
