package com.playfieldportal.feature.xmb.ui

import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Drilled into a sub-item the XMB collapses focus onto the active column, hiding every category to
 * its right. These must be DROPPED from the row rather than rendered empty: an emptied item still
 * takes a slot, and the trailing phantoms left the caticon bar's LazyRow with no scroll headroom —
 * a re-measure (every resume re-measures, because the layout adjustment resolves asynchronously)
 * clamped the scroll short and parked the selected caticon a whole slot right, on top of the game
 * column.
 */
class XmbCategoryBarVisibilityTest {

    private fun category(id: String, position: Int) = Category(
        id = id,
        name = id,
        iconKey = id,
        type = CategoryType.BUILT_IN,
        position = position,
    )

    // The PSP order: the drill in the bug report was Game, with Video immediately to its left.
    private val categories = listOf("settings", "photo", "music", "video", "game", "network")
        .mapIndexed { i, id -> category(id, i) }

    @Test
    fun `undrilled, every category is laid out`() {
        assertSame(categories, visibleCategories(categories, selectedIndex = 4, drilledIn = false))
    }

    @Test
    fun `drilled in, the categories right of the active one are dropped`() {
        val visible = visibleCategories(categories, selectedIndex = 4, drilledIn = true)

        assertEquals(listOf("settings", "photo", "music", "video", "game"), visible.map { it.id })
    }

    @Test
    fun `the active category is the last laid-out slot, so nothing trails it`() {
        val visible = visibleCategories(categories, selectedIndex = 4, drilledIn = true)

        assertEquals(visible.lastIndex, 4)
    }

    @Test
    fun `surviving indices still line up with the source list`() {
        val visible = visibleCategories(categories, selectedIndex = 3, drilledIn = true)

        // isSelected and the click callbacks index into this list with the ORIGINAL selectedIndex.
        visible.forEachIndexed { i, c -> assertEquals(categories[i].id, c.id) }
        assertEquals("video", visible[3].id)
    }

    @Test
    fun `drilling into the first category leaves exactly one slot`() {
        val visible = visibleCategories(categories, selectedIndex = 0, drilledIn = true)

        assertEquals(listOf("settings"), visible.map { it.id })
    }

    @Test
    fun `drilling into the last category drops nothing`() {
        val visible = visibleCategories(categories, selectedIndex = categories.lastIndex, drilledIn = true)

        assertEquals(categories.map { it.id }, visible.map { it.id })
    }

    @Test
    fun `a selection that is not a real index hides nothing`() {
        // An empty bar is worse than an unfiltered one: before the fix, -1 hid every category.
        assertSame(categories, visibleCategories(categories, selectedIndex = -1, drilledIn = true))
        assertSame(categories, visibleCategories(categories, selectedIndex = 99, drilledIn = true))
    }

    @Test
    fun `an empty category list survives every combination`() {
        assertEquals(emptyList(), visibleCategories(emptyList(), selectedIndex = 0, drilledIn = true))
        assertEquals(emptyList(), visibleCategories(emptyList(), selectedIndex = -1, drilledIn = false))
    }
}
