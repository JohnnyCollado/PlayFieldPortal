package com.playfieldportal.feature.settings.viewmodel

import com.playfieldportal.core.domain.model.ConfirmBackLayout
import com.playfieldportal.core.domain.model.ControllerDisplayType
import com.playfieldportal.core.domain.model.ControllerLayoutPrefs
import com.playfieldportal.core.domain.model.TouchNavButtonMode
import com.playfieldportal.core.domain.text.UiText
import com.playfieldportal.core.domain.text.labelRes
import com.playfieldportal.feature.settings.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** Copy for the new Initial Setup rows and the Finish summary (setup-wizard plan sections 3.1–3.6). */
class SetupSummaryTest {

    // ── Controller ──────────────────────────────────────────────────────────────

    @Test fun `controller summary names the pad and its confirm button`() {
        assertEquals(
            UiText.res(R.string.setup_controller_summary, UiText.res(ControllerDisplayType.XBOX.labelRes), UiText.raw("A")),
            controllerSummary(ControllerLayoutPrefs()),
        )
        assertEquals(
            UiText.res(R.string.setup_controller_summary, UiText.res(ControllerDisplayType.XBOX.labelRes), UiText.raw("B")),
            controllerSummary(ControllerLayoutPrefs(confirmBackLayout = ConfirmBackLayout.REVERSED)),
        )
        assertEquals(
            UiText.res(R.string.setup_controller_summary, UiText.res(ControllerDisplayType.PLAYSTATION.labelRes), UiText.res(R.string.setup_button_cross)),
            controllerSummary(ControllerLayoutPrefs(displayType = ControllerDisplayType.PLAYSTATION)),
        )
        assertEquals(
            UiText.res(R.string.setup_controller_summary, UiText.res(ControllerDisplayType.PLAYSTATION.labelRes), UiText.res(R.string.setup_button_circle)),
            controllerSummary(
                ControllerLayoutPrefs(
                    displayType = ControllerDisplayType.PLAYSTATION,
                    confirmBackLayout = ConfirmBackLayout.REVERSED,
                ),
            ),
        )
        assertEquals(
            UiText.res(R.string.setup_controller_summary, UiText.res(ControllerDisplayType.NINTENDO.labelRes), UiText.raw("A")),
            controllerSummary(ControllerLayoutPrefs(displayType = ControllerDisplayType.NINTENDO)),
        )
    }

    @Test fun `A B swap sublabel spells out which button confirms`() {
        assertEquals(
            UiText.res(R.string.setup_confirm_swap_off, "A", "B"),
            confirmSwapSublabel(ControllerLayoutPrefs()),
        )
        assertEquals(
            UiText.res(R.string.setup_confirm_swap_on, "B", "A"),
            confirmSwapSublabel(ControllerLayoutPrefs(confirmBackLayout = ConfirmBackLayout.REVERSED)),
        )
    }

    // ── Hints & Touch ───────────────────────────────────────────────────────────

    @Test fun `hint delay reads in whole or half seconds`() {
        assertEquals(UiText.plural(R.plurals.setup_hints_delay_seconds, 1), hintDelayLabel(1f))
        assertEquals(UiText.plural(R.plurals.setup_hints_delay_seconds, 2), hintDelayLabel(2f))
        assertEquals(UiText.res(R.string.setup_hints_delay_fraction, "2.5"), hintDelayLabel(2.5f))
    }

    @Test fun `hint delay steps to the next whole second and wraps after five`() {
        assertEquals(2f, nextHintDelay(1f))
        assertEquals(3f, nextHintDelay(2.5f))
        assertEquals(5f, nextHintDelay(4f))
        assertEquals(1f, nextHintDelay(5f))
    }

    @Test fun `touch button modes read like Display settings`() {
        assertEquals(UiText.res(R.string.setup_touch_auto), touchButtonLabel(TouchNavButtonMode.AUTO))
        assertEquals(UiText.res(R.string.setup_touch_always_show), touchButtonLabel(TouchNavButtonMode.ALWAYS_SHOW))
        assertEquals(UiText.res(R.string.setup_touch_always_hide), touchButtonLabel(TouchNavButtonMode.ALWAYS_HIDE))
    }

    @Test fun `button hints summary`() {
        assertEquals(
            UiText.res(R.string.setup_hints_summary_on, UiText.plural(R.plurals.setup_hints_delay_seconds, 2)),
            hintsSummary(InterfaceHints(enabled = true, delaySeconds = 2f)),
        )
        assertEquals(UiText.res(R.string.setup_off), hintsSummary(InterfaceHints(enabled = false, delaySeconds = 2f)))
    }

    // ── Finish summary ──────────────────────────────────────────────────────────

    @Test fun `home app summary`() {
        assertEquals(UiText.res(R.string.setup_home_is_pfp), homeAppSummary(isHome = true))
        assertEquals(UiText.res(R.string.setup_home_not_pfp), homeAppSummary(isHome = false))
    }

    @Test fun `optional folder rows read Not set when unlinked`() {
        assertEquals(UiText.res(R.string.setup_not_set), folderSummary(null))
        assertEquals(UiText.raw("dev_hdd0"), folderSummary("dev_hdd0"))
    }
}
