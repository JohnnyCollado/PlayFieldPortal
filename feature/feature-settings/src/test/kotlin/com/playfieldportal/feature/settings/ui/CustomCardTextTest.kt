package com.playfieldportal.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.playfieldportal.core.domain.model.Category
import com.playfieldportal.core.domain.model.CategoryType
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.core.ui.components.PfpModalSpec
import com.playfieldportal.feature.settings.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings ▸ Custom Memory Cards words a card by what it holds, now that it can hold apps.
 * Survey L28 (docs/audits/codebase-survey-2026-10-22.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomCardTextTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun english(text: UiText): String = text.resolve(composeRule.activity)

    @Test
    fun `contents are counted as games or apps`() {
        assertEquals("1 game", english(cardContentsLabel(1, holdsApps = false)))
        assertEquals("3 games", english(cardContentsLabel(3, holdsApps = false)))
        assertEquals("1 app", english(cardContentsLabel(1, holdsApps = true)))
        assertEquals("0 apps", english(cardContentsLabel(0, holdsApps = true)))
    }

    @Test
    fun `the picker says which kind of card each category makes`() {
        val games = Category("games", "Game", "ic", type = CategoryType.BUILT_IN, position = 0, isGamingCategory = true)
        val network = Category("network", "Network", "ic", type = CategoryType.BUILT_IN, position = 1)
        assertEquals(UiText.res(R.string.lib_coll_card_kind_game), cardCategoryDetail(games))
        assertEquals(UiText.res(R.string.lib_coll_card_kind_app), cardCategoryDetail(network))
    }

    // Survey P6: the category picker is the shared Choice modal, so a pad can drive it.
    @Test
    fun `the category picker is a choice of every card category, opening on the first`() {
        val games = Category("games", "Game", "ic", type = CategoryType.BUILT_IN, position = 0, isGamingCategory = true)
        val network = Category("network", "Network", "ic", type = CategoryType.BUILT_IN, position = 1)
        val picked = mutableListOf<String>()
        lateinit var spec: PfpModalSpec.Choice
        composeRule.setContent {
            spec = cardCategoryChoiceSpec("Retro", listOf(games, network), onPick = { picked += it }, onCancel = {})
        }
        composeRule.waitForIdle()

        assertEquals(listOf("Game", "Network"), spec.options.map { it.label })
        assertEquals(listOf("Game card", "App card"), spec.options.map { it.detail })
        spec.onConfirm(1)
        assertEquals(listOf("network"), picked)
    }

    @Test
    fun `the icon step lists the default first, then every catalog icon`() {
        val rows = cardIconRows()
        assertEquals(null, rows.first().key)
        assertEquals(UiText.res(R.string.lib_coll_default_icon), rows.first().label)
        assertEquals(com.playfieldportal.core.ui.icons.CATEGORY_ICON_CATALOG.size + 1, rows.size)
    }
}
