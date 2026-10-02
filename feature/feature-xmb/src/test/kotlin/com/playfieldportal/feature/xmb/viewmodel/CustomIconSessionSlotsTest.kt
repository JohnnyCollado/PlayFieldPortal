package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import com.playfieldportal.themekit.CustomizableIcons
import com.playfieldportal.themekit.IconSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Customize XMB Icons ▸ Category Bar: the user's own categories follow the built-in catbar
 * slots, keyed `usercat_<id>`. The other groups are untouched.
 */
class CustomIconSessionSlotsTest {

    private fun cat(id: String, name: String = id, iconKey: String = "ic_games", visible: Boolean = true) =
        Category(
            id = id, name = name, iconKey = iconKey, type = CategoryType.MANUAL,
            position = 0, isVisible = visible,
        )

    private val bar = listOf(
        cat("games"),
        cat("custom_retro_shelf_9", name = "Retro Shelf", iconKey = "ic_favorites"),
        cat("settings"),
        cat("custom_ff_5", name = "FF"),
        cat("music_apps"),
    )

    private fun session(
        group: IconSlot.Group = IconSlot.Group.CATEGORY_BAR,
        slotIndex: Int = 0,
    ) = CustomIconSession(
        groups = listOf(
            IconSlot.Group.CATEGORY_BAR, IconSlot.Group.ITEMS,
            IconSlot.Group.STATUS, IconSlot.Group.CONSOLE,
        ),
        groupIndex = when (group) {
            IconSlot.Group.CATEGORY_BAR -> 0
            IconSlot.Group.ITEMS -> 1
            IconSlot.Group.STATUS -> 2
            IconSlot.Group.CONSOLE -> 3
        },
        slotIndex = slotIndex,
        userCategorySlots = userCategoryIconSlots(bar),
    )

    private val builtIns = CustomizableIcons.group(IconSlot.Group.CATEGORY_BAR)

    @Test
    fun `category bar is the built-in slots then one per user category in bar order`() {
        val keys = session().slots().map { it.key }
        assertEquals(
            builtIns.map { it.key } + listOf("usercat_custom_retro_shelf_9", "usercat_custom_ff_5"),
            keys,
        )
    }

    @Test
    fun `built-in and non-matching ids are not added`() {
        assertEquals(
            listOf("custom_retro_shelf_9", "custom_ff_5"),
            userCategoryIconSlots(bar).map { it.categoryId },
        )
    }

    @Test
    fun `hidden user categories are not listed`() {
        assertEquals(
            emptyList<UserCategoryIconSlot>(),
            userCategoryIconSlots(listOf(cat("custom_x_1", visible = false))),
        )
    }

    @Test
    fun `other groups are unchanged`() {
        for (group in listOf(IconSlot.Group.ITEMS, IconSlot.Group.STATUS, IconSlot.Group.CONSOLE)) {
            assertEquals(CustomizableIcons.group(group), session(group).slots())
        }
    }

    @Test
    fun `focusedSlot at the built-in count is the first user slot`() {
        val focused = session(slotIndex = builtIns.size).focusedSlot
        assertNotNull(focused)
        assertEquals("usercat_custom_retro_shelf_9", focused!!.key)
    }

    @Test
    fun `focusedSlot inside the built-ins is unchanged`() {
        assertEquals(builtIns[2], session(slotIndex = 2).focusedSlot)
    }

    @Test
    fun `a user slot carries the category name and icon key`() {
        val s = session()
        val slot = s.slots().first { it.key == "usercat_custom_retro_shelf_9" }
        assertEquals("Retro Shelf", slot.displayName)
        assertEquals("ic_favorites", s.userCategorySlots.first { it.key == slot.key }.iconKey)
    }
}
