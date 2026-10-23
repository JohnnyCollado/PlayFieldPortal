package com.playfieldportal.feature.settings.ui

import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Settings ▸ Custom Memory Cards words a card by what it holds, now that it can hold apps.
 * Survey L28 (docs/audits/codebase-survey-2026-10-22.md).
 */
class CustomCardTextTest {

    @Test
    fun `contents are counted as games or apps`() {
        assertEquals("1 game", cardContentsLabel(1, holdsApps = false))
        assertEquals("3 games", cardContentsLabel(3, holdsApps = false))
        assertEquals("1 app", cardContentsLabel(1, holdsApps = true))
        assertEquals("0 apps", cardContentsLabel(0, holdsApps = true))
    }

    @Test
    fun `the picker says which kind of card each category makes`() {
        val games = Category("games", "Game", "ic", type = CategoryType.BUILT_IN, position = 0, isGamingCategory = true)
        val network = Category("network", "Network", "ic", type = CategoryType.BUILT_IN, position = 1)
        assertEquals("Game card", cardCategoryDetail(games))
        assertEquals("App card", cardCategoryDetail(network))
    }

    // Survey P6: the category picker is the shared Choice modal, so a pad can drive it.
    @Test
    fun `the category picker is a choice of every card category, opening on the first`() {
        val games = Category("games", "Game", "ic", type = CategoryType.BUILT_IN, position = 0, isGamingCategory = true)
        val network = Category("network", "Network", "ic", type = CategoryType.BUILT_IN, position = 1)
        val picked = mutableListOf<String>()
        val spec = cardCategoryChoiceSpec("Retro", listOf(games, network), onPick = { picked += it }, onCancel = {})

        assertEquals(listOf("Game", "Network"), spec.options.map { it.label })
        assertEquals(listOf("Game card", "App card"), spec.options.map { it.detail })
        spec.onConfirm(1)
        assertEquals(listOf("network"), picked)
    }

    @Test
    fun `the icon step lists the default first, then every catalog icon`() {
        val rows = cardIconRows()
        assertEquals(null, rows.first().key)
        assertEquals("Default (Memory Card)", rows.first().label)
        assertEquals(com.playfieldportal.core.ui.icons.CATEGORY_ICON_CATALOG.size + 1, rows.size)
    }
}
