package com.playfieldportal.feature.xmb.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The menus of the rows a column is built from: a custom memory card, a console card, All Games,
 * and the rows with no record of their own (a category's Memory Card, Favorites, Missing) — plus
 * the Add to Card picker. Each carries the list's Sort the way it already carried Icon Display,
 * and Move only where a move would stick.
 */
class ArrangeMenusTest {

    // ── A custom memory card row ──────────────────────────────────────────────

    private fun cardMenu(
        pinned: Boolean = false,
        canMoveToCategory: Boolean = true,
        canMove: Boolean = false,
    ) = customCardMenuItems(pinned, canMoveToCategory, sortValue = "Global: Title", canMove = canMove)

    @Test
    fun `a custom card's menu uses card wording throughout`() {
        val labels = cardMenu().map { it.label }
        assertEquals(
            listOf("Open", "Sort", "Pin to Top", "Rename Card", "Move to Category", "Manage Custom Cards", "Delete Custom Card"),
            labels,
        )
        assertFalse(labels.any { it.contains("Collection") })
    }

    @Test
    fun `deleting a custom card is the only destructive row`() {
        assertEquals(listOf("delete_collection"), cardMenu().filter { it.isDestructive }.map { it.id })
    }

    @Test
    fun `a custom card's sort row shows the sort of the games inside it`() {
        assertEquals("Global: Title", cardMenu().single { it.id == LIST_SORT_ROW_ID }.value)
    }

    @Test
    fun `pin to top reads on and off under one label`() {
        val off = cardMenu(pinned = false).single { it.label == "Pin to Top" }
        val on = cardMenu(pinned = true).single { it.label == "Pin to Top" }
        assertEquals("pin_collection" to "Off", off.id to off.value)
        assertEquals("unpin_collection" to "On", on.id to on.value)
    }

    @Test
    fun `move appears on a custom card only while its root is custom sorted`() {
        assertFalse(cardMenu(canMove = false).any { it.id == MOVE_ROW_ID })
        assertTrue(cardMenu(canMove = true).any { it.id == MOVE_ROW_ID })
    }

    @Test
    fun `move to category is hidden when there is nowhere to move to`() {
        assertFalse(cardMenu(canMoveToCategory = false).any { it.id == "move_collection_category" })
    }

    // ── Console cards and All Games ───────────────────────────────────────────

    @Test
    fun `a console card carries its own sort beside its own icon display`() {
        val rows = platformCardMenuItems("gba", pinned = false, iconDisplayLabel = "Global: ICON0", sortLabel = "Custom")
        val ids = rows.map { it.id }
        assertEquals(ids.indexOf("icon_display_platform") + 1, ids.indexOf(LIST_SORT_ROW_ID))
        assertEquals("Custom", rows.single { it.id == LIST_SORT_ROW_ID }.value)
    }

    @Test
    fun `a console card offers move only while the games root is custom sorted`() {
        assertFalse(platformCardMenuItems("gba", false, "x", "Global: Title", canMove = false).any { it.id == MOVE_ROW_ID })
        assertTrue(platformCardMenuItems("gba", false, "x", "Global: Title", canMove = true).any { it.id == MOVE_ROW_ID })
    }

    @Test
    fun `all games carries both its own sort and the global one`() {
        val rows = allGamesMenuItems("ICON0", sortLabel = "Global: Title", globalSortLabel = "Title")
        assertEquals("Global: Title", rows.single { it.id == LIST_SORT_ROW_ID }.value)
        assertEquals("Global Sort", rows.single { it.id == GLOBAL_SORT_ROW_ID }.label)
        assertEquals("Title", rows.single { it.id == GLOBAL_SORT_ROW_ID }.value)
    }

    @Test
    fun `the card menus are unchanged for callers that pass no sort`() {
        assertFalse(platformCardMenuItems("gba", false, "x").any { it.id == LIST_SORT_ROW_ID })
        assertFalse(allGamesMenuItems("x").any { it.id == LIST_SORT_ROW_ID || it.id == GLOBAL_SORT_ROW_ID })
    }

    // ── Rows with no record of their own ──────────────────────────────────────

    @Test
    fun `a category's memory card can be opened, sorted and moved`() {
        val rows = rootRowMenuItems(sortValue = "Global: Title", canMove = true)
        assertEquals(listOf("open_row", LIST_SORT_ROW_ID, MOVE_ROW_ID), rows.map { it.id })
    }

    @Test
    fun `a root row can never be pinned`() {
        val rows = rootRowMenuItems(sortValue = "Global: Title", canMove = true)
        assertFalse(rows.any { it.id == "pin_top" || it.id == "unpin_top" })
    }

    @Test
    fun `the missing bucket has no sort row`() {
        assertEquals(listOf("open_row"), rootRowMenuItems(sortValue = null, canMove = false).map { it.id })
    }

    // ── Add to Card ───────────────────────────────────────────────────────────

    private val cards = listOf(
        Triple(1L, "RPG", "games"),
        Triple(2L, "Tactics", "custom_ff_5"),
        Triple(3L, "Mainline", "custom_ff_5"),
    )
    private val names = mapOf("games" to "Game", "custom_ff_5" to "Final Fantasy")

    @Test
    fun `add to card lists the current category's cards first and a new card last`() {
        val rows = addToCardMenuItems(cards, currentCategoryId = "custom_ff_5", categoryNames = names, memberOf = emptySet())
        assertEquals(listOf("col_2", "col_3", "col_1", "col_new"), rows.map { it.id })
        assertEquals("New Custom Card Here…", rows.last().label)
    }

    @Test
    fun `add to card heads each group and names where another category's card lives`() {
        val rows = addToCardMenuItems(cards, currentCategoryId = "custom_ff_5", categoryNames = names, memberOf = emptySet())
        assertEquals("In Final Fantasy", rows[0].header)
        assertNull(rows[1].header)
        assertEquals("Other Categories", rows[2].header)
        assertEquals("Game", rows[2].value)
        assertNull(rows[0].value)
    }

    @Test
    fun `add to card checks the cards the game is already in`() {
        val rows = addToCardMenuItems(cards, currentCategoryId = "custom_ff_5", categoryNames = names, memberOf = setOf(3L))
        assertEquals(listOf("col_3"), rows.filter { it.checked }.map { it.id })
    }

    @Test
    fun `a category with no cards of its own still offers the others and a new card`() {
        val rows = addToCardMenuItems(cards.take(1), currentCategoryId = "custom_ff_5", categoryNames = names, memberOf = emptySet())
        assertEquals(listOf("col_1", "col_new"), rows.map { it.id })
        // No "here" group above it, so no "Other Categories" header to set it apart from.
        assertNull(rows[0].header)
    }
}
