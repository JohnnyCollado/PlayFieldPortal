package com.playfieldportal.feature.xmb.viewmodel

import com.playfieldportal.core.domain.model.GamepadAction
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a pad button does while the Discord QR overlay is up.
 *
 * Survey P1 (docs/audits/codebase-survey-2026-10-22.md): the router swallowed everything but Back,
 * so Try again and Copy code were touch-only.
 */
class DiscordLoginInputTest {

    @Test
    fun `Back closes, Confirm reaches the overlay, everything else is ignored`() {
        assertEquals(DiscordLoginInput.CLOSE, discordLoginInput(GamepadAction.BACK))
        assertEquals(DiscordLoginInput.CONFIRM, discordLoginInput(GamepadAction.SELECT))
        assertEquals(DiscordLoginInput.IGNORE, discordLoginInput(GamepadAction.NAVIGATE_LEFT))
        assertEquals(DiscordLoginInput.IGNORE, discordLoginInput(GamepadAction.OPEN_CONTEXT_MENU))
    }
}
