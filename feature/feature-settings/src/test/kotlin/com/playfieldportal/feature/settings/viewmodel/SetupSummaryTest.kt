package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.domain.model.ConfirmBackLayout
import com.playfieldportal.core.domain.model.ControllerDisplayType
import com.playfieldportal.core.domain.model.ControllerLayoutPrefs
import com.playfieldportal.core.domain.model.TouchNavButtonMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** Copy for the new Initial Setup rows and the Finish summary (setup-wizard plan sections 3.1–3.6). */
class SetupSummaryTest {

    // ── Controller ──────────────────────────────────────────────────────────────

    @Test fun `controller summary names the pad and its confirm button`() {
        assertEquals("Xbox · A confirms", controllerSummary(ControllerLayoutPrefs()))
        assertEquals(
            "Xbox · B confirms",
            controllerSummary(ControllerLayoutPrefs(confirmBackLayout = ConfirmBackLayout.REVERSED)),
        )
        assertEquals(
            "PlayStation · Cross confirms",
            controllerSummary(ControllerLayoutPrefs(displayType = ControllerDisplayType.PLAYSTATION)),
        )
        assertEquals(
            "PlayStation · Circle confirms",
            controllerSummary(
                ControllerLayoutPrefs(
                    displayType = ControllerDisplayType.PLAYSTATION,
                    confirmBackLayout = ConfirmBackLayout.REVERSED,
                ),
            ),
        )
        assertEquals(
            "Nintendo · A confirms",
            controllerSummary(ControllerLayoutPrefs(displayType = ControllerDisplayType.NINTENDO)),
        )
    }

    @Test fun `A B swap sublabel spells out which button confirms`() {
        assertEquals("Off — A confirms, B goes back", confirmSwapSublabel(ControllerLayoutPrefs()))
        assertEquals(
            "On — B confirms, A goes back",
            confirmSwapSublabel(ControllerLayoutPrefs(confirmBackLayout = ConfirmBackLayout.REVERSED)),
        )
    }

    // ── Hints & Touch ───────────────────────────────────────────────────────────

    @Test fun `hint delay reads in whole or half seconds`() {
        assertEquals("1 second", hintDelayLabel(1f))
        assertEquals("2 seconds", hintDelayLabel(2f))
        assertEquals("2.5 seconds", hintDelayLabel(2.5f))
    }

    @Test fun `hint delay steps to the next whole second and wraps after five`() {
        assertEquals(2f, nextHintDelay(1f))
        assertEquals(3f, nextHintDelay(2.5f))
        assertEquals(5f, nextHintDelay(4f))
        assertEquals(1f, nextHintDelay(5f))
    }

    @Test fun `touch button modes read like Display settings`() {
        assertEquals("Auto", touchButtonLabel(TouchNavButtonMode.AUTO))
        assertEquals("Always Show", touchButtonLabel(TouchNavButtonMode.ALWAYS_SHOW))
        assertEquals("Always Hide", touchButtonLabel(TouchNavButtonMode.ALWAYS_HIDE))
    }

    @Test fun `button hints summary`() {
        assertEquals("On · 2 seconds", hintsSummary(InterfaceHints(enabled = true, delaySeconds = 2f)))
        assertEquals("Off", hintsSummary(InterfaceHints(enabled = false, delaySeconds = 2f)))
    }

    // ── Finish summary ──────────────────────────────────────────────────────────

    @Test fun `home app summary`() {
        assertEquals("Play Field Portal", homeAppSummary(isHome = true))
        assertEquals("Not Play Field Portal", homeAppSummary(isHome = false))
    }

    @Test fun `optional folder rows read Not set when unlinked`() {
        assertEquals("Not set", folderSummary(null))
        assertEquals("dev_hdd0", folderSummary("dev_hdd0"))
    }
}
