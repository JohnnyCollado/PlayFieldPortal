package com.playfieldportal.feature.xmb.viewmodel

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * START is bound to [com.playfieldportal.core.domain.model.GamepadAction.HOME], which already
 * means Confirm inside the pickers and meant nothing at all on the XMB. Opening the panel is one
 * line replacing that dead `-> Unit`, and [startOutcome] is the single rule behind it, so the way
 * in and the way out cannot drift apart.
 *
 * The dispatcher consults it *below* the picker and context-menu branches, which consume START and
 * return before it is reached. That ordering is why a picker state reads as
 * [StartOutcome.FORWARD_TO_OVERLAY] here rather than as a case of its own.
 */
class NotificationPanelGateTest {

    @Test
    fun `START on the plain XMB opens the panel`() {
        assertEquals(StartOutcome.OPEN_PANEL, startOutcome(XMBUiState(showBootSequence = false)))
    }

    @Test
    fun `START with the panel open closes it`() {
        val open = XMBUiState(showBootSequence = false, notificationPanel = NotificationPanelState())
        assertEquals(StartOutcome.CLOSE_PANEL, startOutcome(open))
    }

    @Test
    fun `START inside a picker is forwarded, so it still confirms`() {
        val inPicker = XMBUiState(showBootSequence = false, gamePickerCategoryId = "games")
        assertEquals(StartOutcome.FORWARD_TO_OVERLAY, startOutcome(inPicker))
    }

    @Test
    fun `START behind another overlay never opens the panel`() {
        val behind = listOf(
            XMBUiState(showBootSequence = true),
            XMBUiState(showBootSequence = false, activeSettingsScreen = "settings_display"),
            XMBUiState(showBootSequence = false, activeGameId = 7L),
            XMBUiState(showBootSequence = false, musicPlayerVisible = true),
            XMBUiState(showBootSequence = false, infoDialog = InfoDialogState("t", "m")),
        )
        for (state in behind) {
            assertEquals(StartOutcome.FORWARD_TO_OVERLAY, startOutcome(state))
        }
    }

    // ── The idle hint ───────────────────────────────────────────────────

    private val openPanel = XMBUiState(
        showBootSequence = false,
        notificationPanel = NotificationPanelState(cursor = 2),
    )

    @Test
    fun `the hint fades in once the idle delay has passed`() {
        assertEquals(false, shouldShowNotificationHint(openPanel, idleMs = 1_000))
        assertEquals(true, shouldShowNotificationHint(openPanel, idleMs = 3_000))
    }

    @Test
    fun `the hint is controller-only`() {
        val touched = openPanel.copy(lastInputWasTouch = true)
        assertEquals(false, shouldShowNotificationHint(touched, idleMs = 10_000))
    }

    @Test
    fun `the hint never shows without the panel open`() {
        val closed = XMBUiState(showBootSequence = false)
        assertEquals(false, shouldShowNotificationHint(closed, idleMs = 10_000))
    }

    @Test
    fun `the hint yields to the context menu it is advertising`() {
        // Offering Options over an open menu would name an action that no longer does that.
        val withMenu = openPanel.copy(activeContextMenu = XMBContextMenu("Notifications", emptyList()))
        assertEquals(false, shouldShowNotificationHint(withMenu, idleMs = 10_000))
    }

    @Test
    fun `turning the hint setting off silences the panel hint too`() {
        val off = openPanel.copy(contextMenuHintEnabled = false)
        assertEquals(false, shouldShowNotificationHint(off, idleMs = 10_000))
    }

    @Test
    fun `an open panel wins over its own blocking-overlay flag`() {
        // The panel is itself a blocking overlay, so without this precedence START would read as
        // "some overlay is up, forward it" and the panel would have no way out but Back.
        val open = XMBUiState(showBootSequence = false, notificationPanel = NotificationPanelState())
        assertEquals(true, open.hasBlockingOverlay)
        assertEquals(StartOutcome.CLOSE_PANEL, startOutcome(open))
    }
}
