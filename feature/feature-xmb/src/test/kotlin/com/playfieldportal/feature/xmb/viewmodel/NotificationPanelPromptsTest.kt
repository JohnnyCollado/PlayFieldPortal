package com.playfieldportal.feature.xmb.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The notification panel's prompt bar.
 *
 * Survey U30 (docs/audits/codebase-survey-2026-10-22.md): Options was always advertised, but with
 * no history its menu has no rows and the press does nothing.
 */
class NotificationPanelPromptsTest {

    private fun labels(selection: PanelSelection, hasHistory: Boolean) =
        notificationPanelPrompts(selection, hasHistory).map { it.label }

    @Test
    fun `with history the bar offers Options`() {
        assertEquals(listOf("Open", "Options", "Close"), labels(PanelSelection.HISTORY, hasHistory = true))
    }

    @Test
    fun `with only running tasks there is no Options to offer`() {
        assertEquals(listOf("Stop", "Close"), labels(PanelSelection.RUNNING, hasHistory = false))
        assertEquals(listOf("Close"), labels(PanelSelection.NONE, hasHistory = false))
    }
}
