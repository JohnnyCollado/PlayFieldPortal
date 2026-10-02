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
        assertTrue(ids.containsAll(listOf("favorite_toggle", "add_to_collection", "change_emulator", "file_location", "remove_game")))
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
        item: XMBItem = game,
        emulatorLabel: String = "Default",
        iconDisplayLabel: String? = null,
    ) = gameContextMenuItems(
        item = item,
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
        emulatorLabel = emulatorLabel,
        iconDisplayLabel = iconDisplayLabel,
    )

    @Test
    fun `a game's card rows say Card, never Collection`() {
        val labels = menu(inCollection = true).map { it.label }
        assertTrue(labels.containsAll(listOf("Add to Card", "Remove from Card", "Manage Custom Cards")))
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

    // ── Mockup 1: groups, glossary wording, values ───────────────────────────

    /** The group each row sits under, by walking the headers the way the panel draws them. */
    private fun List<XMBContextMenuItem>.groups(): Map<String, String> {
        var current = ""
        return associate { row ->
            row.header?.let { current = it }
            row.id to current
        }
    }

    private val pcGame = XMBItem(id = "w1", title = "Hades", gameId = 3L, platformId = "windows", isRealGame = true)

    @Test
    fun `the groups run Play, Library, Arrange, Customize, Manage in order`() {
        val headers = menu(pinned = false, canMove = true, currentCategory = custom).mapNotNull { it.header }
        assertEquals(listOf("Play", "Library", "Arrange", "Customize", "Manage"), headers)
    }

    @Test
    fun `a PC game gets a PC group between Customize and Manage`() {
        val headers = menu(item = pcGame, pinned = false).mapNotNull { it.header }
        assertEquals(listOf("Play", "Library", "Arrange", "Customize", "PC", "Manage"), headers)
    }

    @Test
    fun `every row sits in its mockup group`() {
        val g = menu(
            item = pcGame, currentCategory = custom, pinned = false, canMove = true,
            categories = listOf(games, custom, custom.copy(id = "custom_rpg_6", position = 10)),
            umd = UmdMenuState.CAN_INSERT, canSelectMultiple = true, inCollection = true,
        ).groups()
        listOf("game_details", "view_shiba_coins", "change_emulator", "insert_umd").forEach { assertEquals(it, "Play", g[it]) }
        listOf("favorite_toggle", "add_to_collection", "remove_from_collection", "select_multiple")
            .forEach { assertEquals(it, "Library", g[it]) }
        listOf("pin_top", "move_row", "move_category", "remove_category").forEach { assertEquals(it, "Arrange", g[it]) }
        listOf("icon_display", "fetch_artwork").forEach { assertEquals(it, "Customize", g[it]) }
        listOf("install_goldberg", "export_game").forEach { assertEquals(it, "PC", g[it]) }
        // Manage Custom Cards stays on the game row, under Manage (Resolved Decision Q2).
        listOf("manage_collections", "file_location").forEach { assertEquals(it, "Manage", g[it]) }
    }

    @Test
    fun `Remove from Library is the last row`() {
        assertEquals("remove_game", menu().last().id)
        assertEquals("Remove from Library", menu().last().label)
    }

    @Test
    fun `a disc set's Choose Disc sits under Play`() {
        val rows = gameContextMenuItems(
            item = game, discCount = 2, inCollection = false, currentCategory = games,
            categories = listOf(games), inMissingBucket = false, hideLabel = null,
        )
        assertEquals("Play", rows.groups()["choose_disc"])
    }

    @Test
    fun `Favorite is one fixed label with an On or Off value, silent`() {
        val off = menu(item = game.copy(isFavorite = false)).single { it.id == "favorite_toggle" }
        val on = menu(item = game.copy(isFavorite = true)).single { it.id == "favorite_toggle" }
        assertEquals("Favorite", off.label)
        assertEquals("Favorite", on.label)
        assertEquals("Off", off.value)
        assertEquals("On", on.value)
        assertTrue(off.silent)
        assertFalse(menu().any { it.id == "favorite" || it.id == "unfavorite" })
    }

    @Test
    fun `Add to Card opens a menu`() {
        val row = menu().single { it.id == "add_to_collection" }
        assertEquals("Add to Card", row.label)
        assertTrue(row.opensMenu)
    }

    @Test
    fun `the file row says Show File Location`() {
        assertEquals("Show File Location", menu().single { it.id == "file_location" }.label)
    }

    @Test
    fun `Change Emulator shows the override, or Default, and opens a menu`() {
        val default = menu().single { it.id == "change_emulator" }
        assertEquals("Default", default.value)
        assertTrue(default.opensMenu)
        assertEquals("Dolphin", menu(emulatorLabel = "Dolphin").single { it.id == "change_emulator" }.value)
    }

    @Test
    fun `Icon Display shows the mode in force and opens a menu`() {
        val row = menu(iconDisplayLabel = "Box Art").single { it.id == "icon_display" }
        assertEquals("Box Art", row.value)
        assertTrue(row.opensMenu)
    }
}
