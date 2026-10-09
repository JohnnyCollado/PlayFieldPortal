package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.feature.xmb.viewmodel.XMBItem
import com.playfieldportal.feature.xmb.viewmodel.XMBItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * All Games is the Game column's memory card, the twin of the Music / Videos / Photos cards: it
 * draws the memory-card art and reads the Games memory-card slot, so a theme or a user pick on
 * that slot reaches it the way it reaches the media cards.
 */
class MemoryCardSlotKeyTest {

    private fun item(id: String, type: XMBItemType, iconKey: String? = null, coverUri: String? = null) =
        XMBItem(id = id, title = id, type = type, iconKey = iconKey, coverUri = coverUri)

    @Test
    fun `all games reads the games memory card slot`() {
        assertEquals("item_memcard_games", memoryCardSlotKeyFor(item("all_games", XMBItemType.ALL_GAMES)))
    }

    @Test
    fun `a custom category's own card is its all games and reads the same slot`() {
        assertEquals("item_memcard_games", memoryCardSlotKeyFor(item("category_card", XMBItemType.CATEGORY_CARD)))
    }

    @Test
    fun `the media cards keep their own slots`() {
        assertEquals("item_memcard_music", memoryCardSlotKeyFor(item("all_music", XMBItemType.MEMORY_CARD)))
        assertEquals("item_memcard_video", memoryCardSlotKeyFor(item("all_videos", XMBItemType.MEMORY_CARD)))
        assertEquals("item_memcard_photos", memoryCardSlotKeyFor(item("all_photos", XMBItemType.MEMORY_CARD)))
    }

    @Test
    fun `console cards keep their console identity`() {
        assertNull(memoryCardSlotKeyFor(item("card_psx", XMBItemType.MEMORY_CARD)))
    }

    @Test
    fun `all games and a category card draw the default memory-card art`() {
        assertEquals(MEMORY_CARD_DEFAULT_ART, memoryCardArtFor(item("all_games", XMBItemType.ALL_GAMES)))
        assertEquals(MEMORY_CARD_DEFAULT_ART, memoryCardArtFor(item("category_card", XMBItemType.CATEGORY_CARD)))
    }

    @Test
    fun `a collection draws the memory card only without a picked icon`() {
        assertEquals(MEMORY_CARD_DEFAULT_ART, memoryCardArtFor(item("collection_1", XMBItemType.COLLECTION)))
        assertNull(memoryCardArtFor(item("collection_2", XMBItemType.COLLECTION, iconKey = "ic_star")))
    }

    @Test
    fun `explicit art wins and console cards and favorites have none`() {
        assertEquals("content://cover", memoryCardArtFor(item("all_music", XMBItemType.MEMORY_CARD, coverUri = "content://cover")))
        assertNull(memoryCardArtFor(item("card_psx", XMBItemType.MEMORY_CARD)))
        assertNull(memoryCardArtFor(item("favorites", XMBItemType.FAVORITES)))
    }
}
