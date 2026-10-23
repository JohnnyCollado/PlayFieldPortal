package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.BuiltInCategory
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a custom memory card made from Add to Card ▸ "New Custom Card Here…" lives, and which kind
 * of card the Add to Card list offers.
 *
 * Survey L30 (docs/audits/codebase-survey-2026-10-22.md): from a column that shows no cards (the
 * Android column, media), the home fell back to Main Game and only game cards were listed — so an
 * app's "New Custom Card Here…" made a GAME card and put the app on it.
 */
class NewCardHomeTest {

    private val games = Category(BuiltInCategory.GAMES, "Game", "ic_games", type = CategoryType.BUILT_IN, position = 4, isGamingCategory = true)
    private val network = Category("network", "Network", "ic_network", type = CategoryType.BUILT_IN, position = 5)
    private val android = Category(BuiltInCategory.ANDROID, "Android", "ic_android", type = CategoryType.BUILT_IN, position = 7)

    @Test
    fun `a column that shows cards is the home, whatever the item is`() {
        assertEquals("network", newCardHome(network, itemIsApp = true))
        assertEquals(BuiltInCategory.GAMES, newCardHome(games, itemIsApp = false))
        assertFalse(wantsGamingCards(network, itemIsApp = true))
        assertTrue(wantsGamingCards(games, itemIsApp = false))
    }

    @Test
    fun `an app from a column without cards gets an app card in App Store`() {
        assertEquals("app_store", newCardHome(android, itemIsApp = true))
        assertFalse(wantsGamingCards(android, itemIsApp = true))
    }

    @Test
    fun `a game from a column without cards still goes to Main Game`() {
        assertEquals(BuiltInCategory.GAMES, newCardHome(android, itemIsApp = false))
        assertTrue(wantsGamingCards(android, itemIsApp = false))
        assertEquals(BuiltInCategory.GAMES, newCardHome(null, itemIsApp = false))
    }
}
