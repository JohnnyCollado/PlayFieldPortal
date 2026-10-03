package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import com.playfieldportal.core.ui.icons.FALLBACK_CATEGORY_ICON
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins mockup 5, the menu a long-press on a category icon opens. */
class CategoryMenuItemsTest {

    private fun rows(
        categoryId: String = "cat_retro",
        iconLabel: String = "Your Image",
        visible: Boolean = true,
    ) = categoryMenuItems(categoryId, iconLabel, visible)

    @Test
    fun `the rows are the mockup's, in order`() {
        assertEquals(
            listOf("Rename Category", "Change Icon", "Show on Bar", "Move", "Manage Categories"),
            rows().map { it.label },
        )
    }

    @Test
    fun `Change Icon shows the icon label and opens a screen`() {
        val row = rows(iconLabel = "Your Image").first { it.id == CATEGORY_MENU_ICON_ID }
        assertEquals("Your Image", row.value)
        assertTrue(row.opensMenu)
    }

    @Test
    fun `Show on Bar reads On while the category is on the bar`() {
        assertEquals("On", rows().first { it.id == CATEGORY_MENU_VISIBLE_ID }.value)
    }

    @Test
    fun `Settings has no Show on Bar`() {
        val labels = rows(categoryId = BuiltInCategory.SETTINGS).map { it.label }
        assertFalse("Show on Bar" in labels)
        assertTrue("Move" in labels)
    }

    @Test
    fun `Move is offered only while the category is on the bar`() {
        assertFalse(rows(visible = false).any { it.id == CATEGORY_MENU_MOVE_ID })
        assertEquals("Off", rows(visible = false).first { it.id == CATEGORY_MENU_VISIBLE_ID }.value)
    }

    @Test
    fun `no row is destructive`() {
        assertTrue(rows().none { it.isDestructive })
    }

    @Test
    fun `the menu is titled with the category and remembers which one it is for`() {
        val category = Category("cat_retro", "Retro Shelf", FALLBACK_CATEGORY_ICON.key, type = CategoryType.MANUAL, position = 3)
        val menu = categoryContextMenu(category, hasImage = false)
        assertEquals("Retro Shelf", menu.title)
        assertEquals("cat_retro", menu.categoryMenuId)
        assertEquals(FALLBACK_CATEGORY_ICON.label, menu.items.first { it.id == CATEGORY_MENU_ICON_ID }.value)
        assertEquals("Your Image", categoryContextMenu(category, hasImage = true).items.first { it.id == CATEGORY_MENU_ICON_ID }.value)
        assertNull(menu.rowKey)
    }
}
