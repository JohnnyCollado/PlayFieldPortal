package com.playfieldportal.feature.xmb.viewmodel

import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.playfieldportal.core.domain.model.TouchGesture
import com.playfieldportal.core.ui.components.TouchPromptItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The words the installed-app picker's removal confirmation and touch footer use. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AppPickerTextTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `the removal question names what the apps leave - a card, or the library`() {
        assertEquals("Remove 2 apps from this card?", removalQuestion(AppPickerTarget.CardApps(7), 2).en())
        assertEquals("Remove 1 app from this library?", removalQuestion(AppPickerTarget.CategoryShortcuts("network"), 1).en())
        assertEquals("Remove 3 apps from this library?", removalQuestion(AppPickerTarget.AndroidGames("android"), 3).en())
    }

    // ── Touch mode (issue #20): a finger must see how to finish, not a HOME-button glyph ──

    @Test
    fun `touch prompts name the tap the grid binds, and step aside for the removal panel`() {
        var open: List<TouchPromptItem> = emptyList()
        var confirming: List<TouchPromptItem> = listOf(TouchPromptItem(TouchGesture.TAP, "unset"))
        composeRule.setContent {
            open = appPickerTouchPrompts(confirmingRemovals = false)
            confirming = appPickerTouchPrompts(confirmingRemovals = true)
        }
        composeRule.waitForIdle()

        assertEquals(listOf(TouchPromptItem(TouchGesture.TAP, "Toggle")), open)
        // The removal panel's own Cancel / Remove buttons are labelled, so the footer is empty.
        assertTrue(confirming.isEmpty())
    }
}
