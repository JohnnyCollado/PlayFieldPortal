package com.playfieldportal.feature.appbar

import com.playfieldportal.core.domain.text.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The rows of an app's context menu, shared by the XMB app row and the App Drawer. */
class AppMenuItemsTest {

    private fun menu(
        byTouch: Boolean = false,
        isFavorite: Boolean = false,
        categoryId: String? = "apps",
        categoryName: String? = "Apps",
        pinned: Boolean = false,
        canMove: Boolean = false,
        isSystemApp: Boolean = false,
        isGame: Boolean = false,
    ) = appMenuItems(AppMenuContext(byTouch, isFavorite, categoryId, categoryName, pinned, canMove, isSystemApp, isGame))

    private fun List<AppMenuEntry>.byId(id: String) = first { it.id == id }

    @Test
    fun `favorite is one fixed row whose value is the state, and it is silent`() {
        val off = menu(isFavorite = false).byId("favorite_toggle")
        val on = menu(isFavorite = true).byId("favorite_toggle")

        assertEquals(UiText.res(R.string.apps_menu_favorite), off.label)
        assertEquals(UiText.res(R.string.apps_value_off), off.value)
        assertEquals(UiText.res(R.string.apps_value_on), on.value)
        assertEquals(UiText.res(R.string.apps_menu_favorite), on.label)
        assertTrue(off.silent)
        assertFalse(menu().any { it.id == "favorite" })
    }

    @Test
    fun `card and category rows use the glossary wording`() {
        val rows = menu()

        assertEquals(UiText.res(R.string.apps_menu_add_to_card), rows.byId("add_to_collection").label)
        assertTrue(rows.byId("add_to_collection").opensMenu)
        assertEquals(UiText.res(R.string.apps_menu_move_to_category), rows.byId("move").label)
        assertTrue(rows.byId("move").opensMenu)
        assertEquals(UiText.res(R.string.apps_menu_add_to_category), rows.byId("add").label)
        assertTrue(rows.byId("add").opensMenu)
        assertEquals(UiText.res(R.string.apps_menu_remove_from_category), rows.byId("remove").label)
        assertFalse(rows.byId("remove").isDestructive)
    }

    @Test
    fun `launch is only offered to a touch opener`() {
        assertFalse(menu(byTouch = false).any { it.id == "launch" })
        assertEquals(UiText.res(R.string.apps_menu_launch), menu(byTouch = true).byId("launch").label)
    }

    @Test
    fun `app info is offered and uninstall is red and last`() {
        val rows = menu()

        assertEquals(UiText.res(R.string.apps_menu_app_info), rows.byId("app_info").label)
        val last = rows.last()
        assertEquals("app_uninstall", last.id)
        assertEquals(UiText.res(R.string.apps_menu_uninstall), last.label)
        assertTrue(last.isDestructive)
    }

    @Test
    fun `a system app cannot be uninstalled`() {
        val rows = menu(isSystemApp = true)

        assertTrue(rows.any { it.id == "app_info" })
        assertFalse(rows.any { it.id == "app_uninstall" })
    }

    @Test
    fun `the groups are Library, Arrange and Manage, each headed on its first row`() {
        val rows = menu(byTouch = true, canMove = true)

        assertEquals(
            listOf(
                UiText.res(R.string.apps_menu_group_library),
                UiText.res(R.string.apps_menu_group_arrange),
                UiText.res(R.string.apps_menu_group_manage),
            ),
            rows.mapNotNull { it.header },
        )
        assertEquals("launch", rows.first().id)
        assertEquals(UiText.res(R.string.apps_menu_group_library), rows.first().header)
        assertEquals("pin", rows.first { it.header == UiText.res(R.string.apps_menu_group_arrange) }.id)
        assertEquals("rename", rows.first { it.header == UiText.res(R.string.apps_menu_group_manage) }.id)
        assertNull(rows.byId("favorite_toggle").header)
    }

    @Test
    fun `pin shows its state and move needs a custom sorted list`() {
        assertEquals("pin", menu(pinned = false).byId("pin").id)
        assertEquals(UiText.res(R.string.apps_value_off), menu(pinned = false).byId("pin").value)
        assertEquals(UiText.res(R.string.apps_value_on), menu(pinned = true).byId("unpin").value)
        assertEquals(UiText.res(R.string.apps_menu_pin_to_top), menu(pinned = true).byId("unpin").label)
        assertFalse(menu(canMove = false).any { it.id == AppMenuIds.MOVE_ROW })
        assertEquals(UiText.res(R.string.apps_menu_move), menu(canMove = true).byId(AppMenuIds.MOVE_ROW).label)
    }

    @Test
    fun `outside a category there is nothing to pin, move, hide-from or remove`() {
        val rows = menu(categoryId = null, categoryName = null, canMove = true)

        val ids = rows.map { it.id }
        assertFalse("pin" in ids || "unpin" in ids)
        assertFalse(AppMenuIds.MOVE_ROW in ids)
        assertFalse("remove" in ids)
        assertFalse("hide_from_category" in ids)
        assertTrue("hide_everywhere" in ids)
    }

    @Test
    fun `hide from names the place the app is shown`() {
        assertEquals(UiText.res(R.string.apps_menu_hide_from, "Tools"), menu(categoryName = "Tools").byId("hide_from_category").label)
    }

    @Test
    fun `mark as game flips to unmark for a game`() {
        assertEquals(UiText.res(R.string.apps_menu_mark_game), menu(isGame = false).byId("mark_game").label)
        assertEquals(UiText.res(R.string.apps_menu_unmark_game), menu(isGame = true).byId("unmark_game").label)
        assertFalse(menu(isGame = true).any { it.id == "mark_game" })
    }

    // ── The App Drawer's menu (mockup 2) ──────────────────────────────────

    private fun drawer(isFavorite: Boolean = false, isGame: Boolean = false, isSystemApp: Boolean = false) =
        appDrawerMenuItems(isFavorite = isFavorite, isGame = isGame, isSystemApp = isSystemApp)

    @Test
    fun `the drawer menu is six ungrouped rows in the mockup's order`() {
        val rows = drawer()

        assertEquals(
            listOf("edit_app", "favorite_toggle", "add_to_collection", "mark_game", "app_info", "app_uninstall"),
            rows.map { it.id },
        )
        assertEquals(
            listOf(
                R.string.apps_menu_edit_details, R.string.apps_menu_favorite, R.string.apps_menu_add_to_card,
                R.string.apps_menu_mark_game, R.string.apps_menu_app_info, R.string.apps_menu_uninstall,
            ).map { UiText.res(it) },
            rows.map { it.label },
        )
        assertTrue(rows.all { it.header == null })
    }

    @Test
    fun `the drawer's favorite and mark as game rows show On or Off`() {
        val off = drawer(isFavorite = false, isGame = false)
        val on = drawer(isFavorite = true, isGame = true)

        assertEquals(UiText.res(R.string.apps_value_off), off.byId("favorite_toggle").value)
        assertEquals(UiText.res(R.string.apps_value_off), off.byId("mark_game").value)
        assertEquals(UiText.res(R.string.apps_value_on), on.byId("favorite_toggle").value)
        // One label and one id for the toggle whichever way it is set: only the value says the state.
        assertEquals(UiText.res(R.string.apps_menu_mark_game), on.single { it.id == "unmark_game" || it.id == "mark_game" }.label)
        assertEquals(UiText.res(R.string.apps_value_on), on.single { it.id == "unmark_game" || it.id == "mark_game" }.value)
        assertTrue(drawer().byId("favorite_toggle").silent)
        assertTrue(drawer().byId("add_to_collection").opensMenu)
    }

    @Test
    fun `the drawer menu keeps uninstall red and drops it for a system app`() {
        assertTrue(drawer().last().isDestructive)
        assertFalse(drawer(isSystemApp = true).any { it.id == "app_uninstall" })
        assertEquals("app_info", drawer(isSystemApp = true).last().id)
    }
}
