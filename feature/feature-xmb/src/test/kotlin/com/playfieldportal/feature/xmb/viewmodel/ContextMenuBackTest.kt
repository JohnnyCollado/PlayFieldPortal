package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import com.playfieldportal.core.domain.model.GamepadAction
import com.playfieldportal.core.ui.components.PspMenuOutcome
import com.playfieldportal.core.ui.components.PspMenuRow
import com.playfieldportal.core.ui.sound.MenuSound
import com.playfieldportal.core.ui.sound.MenuSoundSink
import com.playfieldportal.feature.xmb.ui.toPspRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Back inside a second-level menu climbs to the menu that opened it; Back on a root closes. */
class ContextMenuBackTest {

    private val card = XMBContextMenu(
        title = "PSP Memory Card",
        items = platformCardMenuItems("psp", pinned = false, iconDisplayLabel = "Global: Box Art"),
        selectedIndex = 3,
        platformId = "psp",
    )

    @Test
    fun `the panel rows carry every field of the menu items, opensMenu and silent included`() {
        val menu = XMBContextMenu(
            title = "Game",
            items = listOf(
                XMBContextMenuItem("a", "Add to Card", opensMenu = true, value = "2", header = "Library"),
                XMBContextMenuItem("b", "Favorite", silent = true, checked = true),
                XMBContextMenuItem("c", "Remove", isDestructive = true),
            ),
        )

        val rows = menu.toPspRows()

        assertEquals(PspMenuRow("Add to Card", opensMenu = true, value = "2", header = "Library"), rows[0])
        assertEquals(PspMenuRow("Favorite", checked = true, silent = true), rows[1])
        assertEquals(PspMenuRow("Remove", isDestructive = true), rows[2])
    }

    private val heard = mutableListOf<MenuSound>()
    private val sounds = MenuSoundSink { heard += it }

    private fun picker(selectedIndex: Int = 0) = XMBContextMenu(
        title = "Icon Display",
        items = listOf(
            XMBContextMenuItem("picondisp_default", "Use Global Setting"),
            XMBContextMenuItem("picondisp_BOX", "Box Art"),
        ),
        selectedIndex = selectedIndex,
        platformId = "psp",
        parent = card,
    )

    @Test
    fun `depth counts the menus above this one, and a Games Filter group counts as one level`() {
        assertEquals(0, card.depth)
        assertEquals(1, picker().depth)
        assertEquals(1, XMBContextMenu("Sort", emptyList(), gamesFilterMenu = true, gamesFilterGroup = GamesFilterGroup.SORT).depth)
        assertEquals(0, XMBContextMenu("Filter", emptyList(), gamesFilterMenu = true).depth)
    }

    @Test
    fun `Back in a picker climbs and plays BACK, Triangle closes outright and plays BACK`() {
        assertEquals(PspMenuOutcome.Up, picker().press(GamepadAction.BACK, sounds))
        assertEquals(listOf(MenuSound.BACK), heard)
        heard.clear()
        assertEquals(PspMenuOutcome.Close, picker().press(GamepadAction.OPEN_CONTEXT_MENU, sounds))
        assertEquals(listOf(MenuSound.BACK), heard)
    }

    @Test
    fun `Back on a root menu closes and plays BACK`() {
        assertEquals(PspMenuOutcome.Close, card.press(GamepadAction.BACK, sounds))
        assertEquals(listOf(MenuSound.BACK), heard)
    }

    @Test
    fun `the cursor clamps without a sound at either end and plays SCROLL when it moves`() {
        assertEquals(PspMenuOutcome.Ignored, picker(selectedIndex = 0).press(GamepadAction.NAVIGATE_UP, sounds))
        assertEquals(PspMenuOutcome.Ignored, picker(selectedIndex = 1).press(GamepadAction.NAVIGATE_DOWN, sounds))
        assertEquals(emptyList<MenuSound>(), heard)
        assertEquals(PspMenuOutcome.Moved(1), picker(selectedIndex = 0).press(GamepadAction.NAVIGATE_DOWN, sounds))
        assertEquals(listOf(MenuSound.SCROLL), heard)
    }

    @Test
    fun `activating a row plays SELECT to open a list, CONFIRM to commit, nothing for a silent row`() {
        val menu = XMBContextMenu(
            title = "Game",
            items = listOf(
                XMBContextMenuItem("a", "Add to Card", opensMenu = true),
                XMBContextMenuItem("b", "Fetch Artwork"),
                XMBContextMenuItem("c", "Favorite", silent = true),
            ),
        )

        menu.copy(selectedIndex = 0).press(GamepadAction.SELECT, sounds)
        menu.copy(selectedIndex = 1).press(GamepadAction.SELECT, sounds)
        menu.copy(selectedIndex = 2).press(GamepadAction.SELECT, sounds)

        assertEquals(listOf(MenuSound.SELECT, MenuSound.CONFIRM), heard)
    }

    @Test
    fun `a menu opening is a menu appearing from nothing, not a picker replacing its parent`() {
        assertEquals(true, contextMenuOpened(before = null, after = card))
        assertEquals(false, contextMenuOpened(before = card, after = picker()))
        assertEquals(false, contextMenuOpened(before = card, after = null))
        assertEquals(false, contextMenuOpened(before = null, after = null))
    }

    @Test
    fun `rows that open a picker say so`() {
        val game = platformCardMenuItems("psp", pinned = false, iconDisplayLabel = "Global", sortLabel = "A-Z")
        assertEquals(true, game.first { it.id == "icon_display_platform" }.opensMenu)
        assertEquals(true, game.first { it.id == LIST_SORT_ROW_ID }.opensMenu)

        val all = allGamesMenuItems(iconDisplayLabel = "Global", sortLabel = "A-Z", globalSortLabel = "A-Z")
        assertEquals(true, all.first { it.id == "icon_display_global" }.opensMenu)
        assertEquals(true, all.first { it.id == LIST_SORT_ROW_ID }.opensMenu)
        assertEquals(true, all.first { it.id == GLOBAL_SORT_ROW_ID }.opensMenu)

        val custom = customCardMenuItems(pinned = false, canMoveToCategory = true, sortValue = "A-Z", canMove = false, byTouch = true)
        assertEquals(true, custom.first { it.id == LIST_SORT_ROW_ID }.opensMenu)
        assertEquals(true, custom.first { it.id == "move_collection_category" }.opensMenu)
        assertEquals(false, custom.first { it.id == "delete_collection" }.opensMenu)

        assertEquals(true, rootRowMenuItems(sortValue = "A-Z", canMove = false, byTouch = true).first { it.id == LIST_SORT_ROW_ID }.opensMenu)
    }

    @Test
    fun `a game's picker rows open menus and its Favorite row is silent`() {
        val games = Category(
            BuiltInCategory.GAMES, "Games", "games",
            type = CategoryType.BUILT_IN, position = 0, isGamingCategory = true,
        )
        val other = Category(
            "custom_ff_5", "Final Fantasy", "games",
            type = CategoryType.MANUAL, position = 9, isGamingCategory = true,
        )
        val rows = gameContextMenuItems(
            item = XMBItem(id = "g", title = "G", gameId = 1L),
            discCount = 2,
            inCollection = false,
            currentCategory = games,
            categories = listOf(games, other),
            inMissingBucket = false,
            hideLabel = null,
        )

        listOf("add_to_collection", "add_category", "change_emulator", "icon_display", "choose_disc").forEach { id ->
            assertEquals("$id opens a menu", true, rows.first { it.id == id }.opensMenu)
        }
        assertEquals(true, rows.first { it.id == "favorite_toggle" }.silent)
        assertEquals(false, rows.first { it.id == "fetch_artwork" }.opensMenu)
    }

    @Test
    fun `Back on a root menu closes it`() {
        assertNull(card.afterBack())
    }

    @Test
    fun `Back in a picker returns to the menu that opened it, cursor where it was left`() {
        val picker = XMBContextMenu(
            title = "Icon Display",
            items = listOf(XMBContextMenuItem("picondisp_default", "Use Global Setting")),
            platformId = "psp",
            parent = card,
        )

        val back = picker.afterBack()

        assertEquals(card, back)
        assertEquals("icon_display_platform", back?.items?.get(back.selectedIndex)?.id)
    }
}
