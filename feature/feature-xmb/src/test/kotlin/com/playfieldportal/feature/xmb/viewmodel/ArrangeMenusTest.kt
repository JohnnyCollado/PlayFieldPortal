package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
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
        byTouch: Boolean = true,
        holds: CardContents = CardContents.GAMES,
    ) = customCardMenuItems(pinned, canMoveToCategory, sortValue = "Global: Title", canMove = canMove, byTouch = byTouch, holds = holds)

    @Test
    fun `a custom card's menu uses card wording throughout`() {
        val labels = cardMenu().map { it.label }
        assertEquals(
            listOf("Open", "Add Games", "Sort", "Pin to Top", "Rename Card", "Move to Category", "Manage Custom Cards", "Delete Custom Card"),
            labels,
        )
        assertFalse(labels.any { it.contains("Collection") })
    }

    @Test
    fun `a controller-opened custom card menu has no Open row but a long-press one keeps it`() {
        assertFalse(cardMenu(byTouch = false).any { it.id == "open_collection" })
        assertEquals("open_collection", cardMenu(byTouch = true).first().id)
        assertEquals(cardMenu(byTouch = true).drop(1), cardMenu(byTouch = false))
    }

    @Test
    fun `a game card offers Add Games first on a controller`() {
        assertEquals("add_games_collection", cardMenu(byTouch = false).first().id)
        assertEquals("open_collection", cardMenu(byTouch = true).first().id)
        assertFalse(cardMenu().any { it.id == ADD_APPS_TO_CARD_ID })
    }

    @Test
    fun `an app card offers Add Apps in the same place and never Add Games`() {
        val apps = cardMenu(byTouch = false, holds = CardContents.APPS)
        assertEquals(ADD_APPS_TO_CARD_ID to "Add Apps", apps.first().id to apps.first().label)
        assertFalse(apps.any { it.id == ADD_GAMES_TO_CARD_ID })
    }

    // ── What confirming the game picker changes ───────────────────────────────

    @Test
    fun `confirm adds only what is new and removes only what the picker opened checked`() {
        val changes = gamePickerChanges(already = setOf(1L, 2L, 9L), preselected = setOf(1L, 2L), selected = setOf(2L, 3L))
        assertEquals(setOf(3L) to setOf(1L), changes)
    }

    @Test
    fun `a game already on the list but never pre-checked is not removed`() {
        // The pre-check failed to load: absence alone must not empty the list.
        assertEquals(setOf(3L) to emptySet<Long>(), gamePickerChanges(already = setOf(1L, 2L), preselected = emptySet(), selected = setOf(3L)))
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
        val rows = rootRowMenuItems(sortValue = "Global: Title", canMove = true, byTouch = true)
        assertEquals(listOf("open_row", LIST_SORT_ROW_ID, MOVE_ROW_ID), rows.map { it.id })
    }

    @Test
    fun `a controller-opened root row menu has no Open row`() {
        val rows = rootRowMenuItems(sortValue = "Global: Title", canMove = true, byTouch = false)
        assertEquals(listOf(LIST_SORT_ROW_ID, MOVE_ROW_ID), rows.map { it.id })
    }

    @Test
    fun `a root row can never be pinned`() {
        val rows = rootRowMenuItems(sortValue = "Global: Title", canMove = true, byTouch = true)
        assertFalse(rows.any { it.id == "pin_top" || it.id == "unpin_top" })
    }

    @Test
    fun `the missing bucket has no sort row`() {
        assertEquals(listOf("open_row"), rootRowMenuItems(sortValue = null, canMove = false, byTouch = true).map { it.id })
    }

    @Test
    fun `the missing bucket has no controller menu but long-press still opens it`() {
        assertTrue(rootRowMenuItems(sortValue = null, canMove = false, byTouch = false).isEmpty())
        val missing = XMBItem(id = "miss", title = "Missing", type = XMBItemType.MISSING)
        val state = XMBUiState(
            categories = listOf(
                Category(BuiltInCategory.GAMES, "Games", "games", type = CategoryType.BUILT_IN, position = 0, isGamingCategory = true),
            ),
            selectedCategoryIndex = 0,
        )
        assertNull(contextMenuTarget(missing, state, byTouch = false))
        assertFalse(missing.hasContextMenu(state))
        assertEquals(ContextMenuKind.ROOT_ROW, contextMenuTarget(missing, state, byTouch = true)?.kind)
    }

    // ── A custom card in an app category ──────────────────────────────────────

    @Test
    fun `an app category's custom card sorts with app sorts, a gaming one with game sorts`() {
        val apps = Category("custom_tools", "Tools", "apps", type = CategoryType.MANUAL, position = 5, isGamingCategory = false)
        val games = Category(BuiltInCategory.GAMES, "Games", "games", type = CategoryType.BUILT_IN, position = 0, isGamingCategory = true)
        assertEquals(XmbListKind.APPS, collectionSortKind(apps))
        assertEquals(XmbListKind.GAMES, collectionSortKind(games))
        assertEquals(XmbListKind.GAMES, collectionSortKind(null))
        val labels = listSortMenuItems(collectionSortKind(apps), global = XmbSortMode.TITLE, override = null).map { it.label }
        assertTrue("A–Z" in labels)
        assertTrue("Recently Used" in labels)
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
