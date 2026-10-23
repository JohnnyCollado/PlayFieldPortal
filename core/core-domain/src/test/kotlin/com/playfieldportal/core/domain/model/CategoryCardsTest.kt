package com.playfieldportal.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which categories hold custom memory cards — one rule shared by the XMB and Settings.
 *
 * Survey L28 (docs/audits/codebase-survey-2026-10-22.md): Settings ▸ Custom Memory Cards offered
 * only gaming categories, while the XMB shows cards in app categories too, so a non-gaming card
 * could not be created from Settings at all.
 */
class CategoryCardsTest {

    private fun cat(id: String, gaming: Boolean = false) =
        Category(id, id, "ic", type = CategoryType.BUILT_IN, position = 0, isGamingCategory = gaming)

    @Test
    fun `gaming and generic app categories hold cards`() {
        assertTrue(cat(BuiltInCategory.GAMES, gaming = true).showsCustomCards())
        assertTrue(cat("network").showsCustomCards())
        assertTrue(cat("app_store").showsCustomCards())
        assertTrue(cat("custom_work_9").showsCustomCards())
    }

    @Test
    fun `media, system and the Android column do not`() {
        listOf(
            BuiltInCategory.MUSIC, BuiltInCategory.VIDEO, BuiltInCategory.PHOTO, BuiltInCategory.ANDROID,
            BuiltInCategory.APP_DRAWER, BuiltInCategory.SETTINGS, BuiltInCategory.ACHIEVEMENTS, BuiltInCategory.SOCIAL,
        ).forEach { assertFalse(it, cat(it).showsCustomCards()) }
    }

    @Test
    fun `cards are listed games first, then apps, keeping bar order inside each group`() {
        val bar = listOf(cat("network"), cat(BuiltInCategory.GAMES, gaming = true), cat("music"), cat("app_store"), cat("custom_rpg_9", gaming = true))
        assertEquals(
            listOf(BuiltInCategory.GAMES, "custom_rpg_9", "network", "app_store"),
            cardCategories(bar).map { it.id },
        )
    }
}
