package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.BackgroundTaskInfo

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The dispatcher's closing guard reads:
 *
 * ```
 * // Defensive net: the main XMB navigation below must NEVER run while any overlay,
 * // menu, or modal dialog is on screen.
 * if (state.hasBlockingOverlay) return
 * ```
 *
 * That comment is written for exactly this case. A panel with its own dispatcher branch but no
 * entry in `hasBlockingOverlay` would let the D-pad drive the crossbar behind it — the branch
 * returns, but every other guard in the file that consults the flag would still say "nothing is
 * on screen". This test is the thing that fails if the second half is ever dropped.
 */
class NotificationBlockingOverlayTest {

    @Test
    fun `an open notification panel is a blocking overlay`() {
        val open = XMBUiState(showBootSequence = false, notificationPanel = NotificationPanelState())
        assertTrue(open.hasBlockingOverlay)
    }

    @Test
    fun `a closed panel blocks nothing on its own`() {
        assertFalse(XMBUiState(showBootSequence = false).hasBlockingOverlay)
    }

    @Test
    fun `neither running work nor unread history blocks the XMB`() {
        // The bell lighting up must not freeze the crossbar; only the open panel does that.
        val busy = XMBUiState(
            showBootSequence = false,
            runningTasks = listOf(BackgroundTaskInfo(id = "scan_psx", label = "Scanning")),
            unreadNotifications = 3,
        )
        assertFalse(busy.hasBlockingOverlay)
    }
}
