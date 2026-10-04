package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import com.playfieldportal.themekit.IconEditorLayout
import com.playfieldportal.themekit.IconEditorTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Customize XMB Icons: the four tabs every editor shares ([IconEditorLayout]), with the Crossbar
 * tab in the live bar's order (the user's own categories where they sit) and the Items tab's
 * column runs following it.
 */
class CustomIconSessionSlotsTest {

    private fun cat(
        id: String,
        iconKey: String,
        name: String = id,
        visible: Boolean = true,
        type: CategoryType = CategoryType.BUILT_IN,
    ) = Category(id = id, name = name, iconKey = iconKey, type = type, position = 0, isVisible = visible)

    // The bar left to right: Games moved first, a user category between Settings and Music,
    // Photo and Social hidden.
    private val bar = listOf(
        cat("games", "ic_games"),
        cat("settings", "ic_settings"),
        cat("custom_retro_shelf_9", "ic_favorites", name = "Retro Shelf", type = CategoryType.MANUAL),
        cat("music", "ic_music"),
        cat("photos", "ic_photos", visible = false),
        cat("videos", "ic_videos"),
        cat("network", "ic_network"),
        cat("app_store", "ic_appstore"),
        cat("social", "ic_social", visible = false),
        cat("achievements", "ic_achievements"),
        cat("custom_ff_5", "ic_games", name = "FF", type = CategoryType.MANUAL),
    )

    private fun session(tab: IconEditorTab = IconEditorTab.CROSSBAR, slotIndex: Int = 0) =
        customIconSessionFor(bar).copy(tabIndex = tab.ordinal, slotIndex = slotIndex)

    @Test
    fun `tabs are the shared four`() {
        assertEquals(IconEditorTab.entries, session().tabs)
    }

    @Test
    fun `bar keys follow the visible bar, user categories in place`() {
        assertEquals(
            listOf(
                "catbar_games", "catbar_settings", "usercat_custom_retro_shelf_9", "catbar_music",
                "catbar_video", "catbar_network", "catbar_appstore", "catbar_achievements", "usercat_custom_ff_5",
            ),
            crossbarEditorKeys(bar),
        )
    }

    @Test
    fun `crossbar is the bar left to right, then the hidden built-ins`() {
        assertEquals(
            listOf(
                "catbar_games", "catbar_settings", "usercat_custom_retro_shelf_9", "catbar_music",
                "catbar_video", "catbar_network", "catbar_appstore", "catbar_achievements", "usercat_custom_ff_5",
                "catbar_photos", "catbar_social",
            ),
            session().slots().map { it.key },
        )
    }

    @Test
    fun `a user slot carries the category name and icon key`() {
        val s = session()
        val slot = s.slots().first { it.key == "usercat_custom_retro_shelf_9" }
        assertEquals("Retro Shelf", slot.displayName)
        assertEquals("ic_favorites", s.userCategorySlots.first { it.key == slot.key }.iconKey)
    }

    @Test
    fun `items follow the bar's column order`() {
        val s = session(IconEditorTab.ITEMS)
        assertEquals(
            listOf("Game", "Settings", "Music", "Video", "Network · App Store", "Shiba Coins", "Photo", "Social"),
            s.runs().map { it.label },
        )
        assertEquals(s.runs().flatMap { run -> run.slots.map { it.key } }, s.slots().map { it.key })
        assertEquals("item_umd", s.slots().first().key)
    }

    @Test
    fun `consoles and physical media are the shared lists`() {
        assertEquals(IconEditorLayout.slots(IconEditorTab.CONSOLES), session(IconEditorTab.CONSOLES).slots())
        assertEquals(IconEditorLayout.slots(IconEditorTab.PHYSICAL_MEDIA), session(IconEditorTab.PHYSICAL_MEDIA).slots())
        assertEquals(emptyList<Any>(), session(IconEditorTab.CONSOLES).runs())
    }

    // ── Column jumps (D-pad up / down on the Items tab) ──────────────────────

    @Test
    fun `down jumps to the next column's first slot`() {
        val s = session(IconEditorTab.ITEMS, slotIndex = 2) // inside Game (5 slots)
        assertEquals(5, s.columnJump(+1)) // Settings starts at 5
        assertEquals(6, s.copy(slotIndex = 5).columnJump(+1)) // Music after one Settings slot
    }

    @Test
    fun `up goes to the start of this column, then the previous one`() {
        val s = session(IconEditorTab.ITEMS)
        assertEquals(6, s.copy(slotIndex = 8).columnJump(-1)) // mid-Music back to Music's start
        assertEquals(5, s.copy(slotIndex = 6).columnJump(-1)) // Music's start back to Settings
    }

    @Test
    fun `column jumps stop at the ends`() {
        val s = session(IconEditorTab.ITEMS)
        assertNull(s.copy(slotIndex = 0).columnJump(-1))
        val lastStart = s.runs().dropLast(1).sumOf { it.slots.size }
        assertNull(s.copy(slotIndex = lastStart + 1).columnJump(+1))
    }

    @Test
    fun `column jumps only apply to the items tab`() {
        assertNull(session(IconEditorTab.CROSSBAR, slotIndex = 2).columnJump(+1))
        assertNull(session(IconEditorTab.CONSOLES, slotIndex = 2).columnJump(-1))
    }

    @Test
    fun `focusedSlot follows the tab and index`() {
        val focused = session(IconEditorTab.ITEMS, slotIndex = 1).focusedSlot
        assertNotNull(focused)
        assertEquals("sysicon_allgames", focused!!.key)
        assertNull(session(IconEditorTab.ITEMS, slotIndex = 999).focusedSlot)
    }

    @Test
    fun `runIndexOf names the focused slot's column`() {
        val s = session(IconEditorTab.ITEMS, slotIndex = 5)
        assertEquals(1, s.focusedRunIndex)
        assertEquals(-1, session(IconEditorTab.CROSSBAR).focusedRunIndex)
    }

    @Test
    fun `hidden user categories are not listed`() {
        assertEquals(
            emptyList<UserCategoryIconSlot>(),
            userCategoryIconSlots(listOf(cat("custom_x_1", "ic_games", visible = false, type = CategoryType.MANUAL))),
        )
    }
}
