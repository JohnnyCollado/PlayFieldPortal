package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins where Fetch Artwork appears in the XMB game menu, and where it deliberately does not. */
class GameContextMenuItemsTest {

    private val games = Category(
        BuiltInCategory.GAMES, "Games", "games",
        type = CategoryType.BUILT_IN, position = 0, isGamingCategory = true,
    )

    private fun items(
        item: XMBItem = XMBItem(id = "g1", title = "Crisis Core", gameId = 1L, platformId = "psp"),
        inMissingBucket: Boolean = false,
    ) = gameContextMenuItems(
        item = item,
        discCount = 0,
        inCollection = false,
        currentCategory = games,
        categories = listOf(games),
        inMissingBucket = inMissingBucket,
        hideLabel = null,
    )

    @Test
    fun `a game offers Fetch Artwork`() {
        val fetch = items().single { it.id == "fetch_artwork" }
        assertEquals("Fetch Artwork", fetch.label)
        assertFalse(fetch.isDestructive)
    }

    @Test
    fun `Fetch Artwork sits right after Icon Display`() {
        val ids = items().map { it.id }
        assertEquals(ids.indexOf("icon_display") + 1, ids.indexOf("fetch_artwork"))
    }

    @Test
    fun `a package-backed Android game offers Fetch Artwork too`() {
        val item = XMBItem(
            id = "a1", title = "Genshin", gameId = 2L, platformId = "android",
            packageName = "com.example.genshin", isAndroidApp = true,
        )
        assertTrue(items(item).any { it.id == "fetch_artwork" })
    }

    @Test
    fun `a missing-ROM entry does not offer Fetch Artwork`() {
        assertFalse(items(inMissingBucket = true).any { it.id == "fetch_artwork" })
    }

    @Test
    fun `extracting the builder keeps the existing entries`() {
        val ids = items().map { it.id }
        assertEquals("game_details", ids.first())
        assertTrue(ids.containsAll(listOf("favorite", "add_to_collection", "change_emulator", "file_location", "remove_game")))
    }
}
