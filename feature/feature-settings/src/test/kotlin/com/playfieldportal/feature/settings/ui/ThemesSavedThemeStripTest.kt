package com.playfieldportal.feature.settings.ui

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import com.playfieldportal.core.data.repository.PfpThemeStore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings > Themes > My Themes: the strip of saved themes is wider than the screen, and the
 * controller moves a cursor the settings scaffold cannot see (it sees one row, not one card). The
 * strip must scroll itself to the focused card — before this, the highlight walked off the right
 * edge and left the focused theme invisible.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w833dp-h1200dp")
class ThemesSavedThemeStripTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    // 8 cards at 168dp + 18dp spacing come to ~1470dp: well past the 833dp viewport.
    private val themes = List(8) { PfpThemeStore.SavedTheme("t$it", "Theme $it", 0xFF20304AL, null) }
    private val focusedIndex = mutableStateOf(0)

    private fun showStrip() = composeRule.setContent {
        SavedThemeCardRow(
            themes = themes,
            focusedIndex = focusedIndex.value,
            onApply = {},
            onDelete = {},
            onShare = {},
            onUpdate = {},
            onOpenMenu = { menus += it },
        )
    }

    private val menus = mutableListOf<String>()

    @Test
    fun `the last card is off-screen until the cursor reaches it`() {
        showStrip()

        composeRule.onNodeWithText("Theme 0").assertIsDisplayed()
        composeRule.onNodeWithText("Theme 7").assertDoesNotExist()
    }

    @Test
    fun `the strip scrolls the focused card into view`() {
        showStrip()

        focusedIndex.value = themes.lastIndex
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Theme 7").assertIsDisplayed()
    }

    @Test
    fun `scrolling back to the first card brings it into view again`() {
        showStrip()

        focusedIndex.value = themes.lastIndex
        composeRule.waitForIdle()
        focusedIndex.value = 0
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Theme 0").assertIsDisplayed()
    }

    // Survey P5 (docs/audits/codebase-survey-2026-10-22.md): the saved theme's menu (Rename Theme)
    // opened only on Triangle, so a touch-only user could not rename a saved theme.
    @Test
    fun `a long press on a card opens its menu`() {
        showStrip()

        composeRule.onNodeWithTag("saved_theme_card_t0").performTouchInput { longClick() }

        assertEquals(listOf("t0"), menus)
    }
}
