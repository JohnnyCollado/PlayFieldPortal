package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The live category Move on the crossbar: left / right slide the lifted category one slot, and the
 * bar is built in the order the user stored, built-ins included.
 */
class CategoryMoveTest {

    private fun cat(id: String, position: Int) =
        Category(id = id, name = id, iconKey = "ic", type = CategoryType.BUILT_IN, position = position)

    private val bar = listOf(cat("settings", 0), cat("music", 1), cat("games", 2), cat("network", 3))

    @Test
    fun `sliding right swaps with the neighbour and the selection follows`() {
        val (moved, index) = moveCategory(bar, index = 1, delta = +1)!!
        assertEquals(listOf("settings", "games", "music", "network"), moved.map { it.id })
        assertEquals(2, index)
    }

    @Test
    fun `sliding left swaps with the neighbour and the selection follows`() {
        val (moved, index) = moveCategory(bar, index = 2, delta = -1)!!
        assertEquals(listOf("settings", "games", "music", "network"), moved.map { it.id })
        assertEquals(1, index)
    }

    @Test
    fun `the ends of the bar stop the slide`() {
        assertNull(moveCategory(bar, index = 0, delta = -1))
        assertNull(moveCategory(bar, index = 3, delta = +1))
    }

    @Test
    fun `a built-in follows its stored position, not the default one`() {
        // The user put Game first in Category Manager; the bar used to keep the default order.
        val fallback = listOf(
            cat(BuiltInCategory.SETTINGS, 0), cat("music", 1), cat(BuiltInCategory.GAMES, 2),
        )
        val stored = listOf(cat(BuiltInCategory.GAMES, 0), cat(BuiltInCategory.SETTINGS, 1), cat("music", 2))
        assertEquals(
            listOf(BuiltInCategory.GAMES, BuiltInCategory.SETTINGS, "music"),
            canonicalXmbCategories(stored, fallback).map { it.id },
        )
    }

    @Test
    fun `a hidden Settings still shows, at its default slot`() {
        val fallback = listOf(cat(BuiltInCategory.SETTINGS, 0), cat("music", 1), cat(BuiltInCategory.GAMES, 2))
        val stored = listOf(cat("music", 1), cat(BuiltInCategory.GAMES, 2))
        assertEquals(
            listOf(BuiltInCategory.SETTINGS, "music", BuiltInCategory.GAMES),
            canonicalXmbCategories(stored, fallback).map { it.id },
        )
    }
}
