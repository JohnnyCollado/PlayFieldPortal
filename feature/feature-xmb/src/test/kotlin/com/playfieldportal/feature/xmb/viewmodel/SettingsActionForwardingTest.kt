package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.GamepadAction
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which presses reach an open settings screen (its scaffold or onInterceptAction). */
class SettingsActionForwardingTest {

    @Test fun `RB and LB reach settings screens so Initial Setup's Skip can fire`() {
        assertTrue(forwardsToSettings(GamepadAction.NEXT_CATEGORY))
        assertTrue(forwardsToSettings(GamepadAction.PREV_CATEGORY))
    }

    @Test fun `navigation, confirm, back and the secondary face buttons still reach settings`() {
        listOf(
            GamepadAction.NAVIGATE_UP, GamepadAction.NAVIGATE_DOWN,
            GamepadAction.NAVIGATE_LEFT, GamepadAction.NAVIGATE_RIGHT,
            GamepadAction.SELECT, GamepadAction.BACK,
            GamepadAction.OPEN_CONTEXT_MENU, GamepadAction.CHANGE_SORT,
        ).forEach { assertTrue(it.name, forwardsToSettings(it)) }
    }

    @Test fun `HOME is never handed to a settings screen`() {
        assertFalse(forwardsToSettings(GamepadAction.HOME))
    }
}
