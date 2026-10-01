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

    // ── Custom memory cards (the user-facing name for collections) ────────────

    private val game = XMBItem(id = "g1", title = "Crisis Core", gameId = 1L, platformId = "psp", isRealGame = true)
    private val custom = Category(
        "custom_ff_5", "Final Fantasy", "games",
        type = CategoryType.MANUAL, position = 9, isGamingCategory = true,
    )

    private fun menu(
        inCollection: Boolean = false,
        currentCategory: Category = games,
        categories: List<Category> = listOf(games, custom),
        pinned: Boolean? = null,
        canMove: Boolean = false,
        umd: UmdMenuState = UmdMenuState.NONE,
        canSelectMultiple: Boolean = false,
    ) = gameContextMenuItems(
        item = game,
        discCount = 0,
        inCollection = inCollection,
        currentCategory = currentCategory,
        categories = categories,
        inMissingBucket = false,
        hideLabel = null,
        pinned = pinned,
        canMove = canMove,
        umd = umd,
        canSelectMultiple = canSelectMultiple,
    )

    @Test
    fun `a game's card rows say Card, never Collection`() {
        val labels = menu(inCollection = true).map { it.label }
        assertTrue(labels.containsAll(listOf("Add to Card…", "Remove from Card", "Manage Custom Cards")))
        assertFalse(labels.any { it.contains("Collection") })
    }

    // ── Pin to Top and Move ──────────────────────────────────────────────────

    @Test
    fun `a game in a list offers Pin to Top with its state`() {
        assertEquals("Off", menu(pinned = false).single { it.id == "pin_top" }.value)
        assertEquals("On", menu(pinned = true).single { it.id == "unpin_top" }.value)
    }

    @Test
    fun `where the list cannot pin there is no pin row`() {
        val ids = menu(pinned = null).map { it.id }
        assertFalse(ids.contains("pin_top"))
        assertFalse(ids.contains("unpin_top"))
    }

    @Test
    fun `move is offered only when the list is custom sorted`() {
        assertFalse(menu(pinned = false, canMove = false).any { it.id == "move_row" })
        assertTrue(menu(pinned = false, canMove = true).any { it.id == "move_row" })
    }

    @Test
    fun `the old category-only pin rows are gone`() {
        val ids = menu(currentCategory = custom, pinned = false).map { it.id }
        assertFalse(ids.contains("pin_category"))
        assertFalse(ids.contains("unpin_category"))
        assertTrue(ids.contains("remove_category"))
    }

    // ── The UMD slot ─────────────────────────────────────────────────────────

    @Test
    fun `a game can be inserted as its column's UMD`() {
        assertEquals("Insert as UMD", menu(umd = UmdMenuState.CAN_INSERT).single { it.id == "insert_umd" }.label)
        assertFalse(menu(umd = UmdMenuState.CAN_INSERT).any { it.id == "eject_umd" })
    }

    @Test
    fun `the inserted game offers Eject instead`() {
        assertEquals("Eject UMD", menu(umd = UmdMenuState.INSERTED).single { it.id == "eject_umd" }.label)
        assertFalse(menu(umd = UmdMenuState.INSERTED).any { it.id == "insert_umd" })
    }

    @Test
    fun `the recently played UMD can be ejected, or inserted to keep it`() {
        val ids = menu(umd = UmdMenuState.RECENT).map { it.id }
        assertTrue(ids.contains("eject_umd"))
        assertTrue(ids.contains("insert_umd"))
        assertTrue(ids.indexOf("insert_umd") < ids.indexOf("eject_umd"))
    }

    @Test
    fun `outside a gaming column there is no UMD row`() {
        val ids = menu(umd = UmdMenuState.NONE).map { it.id }
        assertFalse(ids.contains("insert_umd"))
        assertFalse(ids.contains("eject_umd"))
    }

    // ── Multi-select ─────────────────────────────────────────────────────────

    @Test
    fun `select multiple is offered in a games list and not elsewhere`() {
        assertTrue(menu(canSelectMultiple = true).any { it.id == "select_multiple" })
        assertFalse(menu(canSelectMultiple = false).any { it.id == "select_multiple" })
    }
}
