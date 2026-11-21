package com.playfieldportal.feature.xmb.viewmodel

import androidx.compose.ui.test.junit4.v2.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The notification panel's prompt bar.
 *
 * Survey U30 (docs/audits/codebase-survey-2026-10-22.md): Options was always advertised, but with
 * no history its menu has no rows and the press does nothing.
 *
 * The prompt labels are read from string resources while composing, so the bars are built inside
 * one composition (a rule allows a single `setContent` per test).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NotificationPanelPromptsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun labels(vararg cases: Pair<PanelSelection, Boolean>): List<List<String>> {
        var built: List<List<String>> = emptyList()
        composeRule.setContent {
            built = cases.map { (selection, hasHistory) ->
                notificationPanelPrompts(selection, hasHistory).map { it.label }
            }
        }
        composeRule.waitForIdle()
        return built
    }

    @Test
    fun `with history the bar offers Options`() {
        assertEquals(
            listOf(listOf("Open", "Options", "Close")),
            labels(PanelSelection.HISTORY to true),
        )
    }

    @Test
    fun `with only running tasks there is no Options to offer`() {
        assertEquals(
            listOf(listOf("Stop", "Close"), listOf("Close")),
            labels(PanelSelection.RUNNING to false, PanelSelection.NONE to false),
        )
    }
}
