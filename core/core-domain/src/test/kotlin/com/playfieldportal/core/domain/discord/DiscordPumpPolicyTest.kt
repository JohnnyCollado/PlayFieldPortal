package com.playfieldportal.core.domain.discord

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How often the Discord SDK's callback pump wakes while nothing is pending. Calls, sign-in and other
 * requests always run at the fast cadence natively; this is only the idle beat.
 */
class DiscordPumpPolicyTest {

    @Test
    fun `on screen the idle pump stays responsive`() {
        assertEquals(DiscordPumpPolicy.FOREGROUND_IDLE_MS, DiscordPumpPolicy.idleIntervalMs(foreground = true))
    }

    @Test
    fun `in the background it wakes once a second, not a hundred times`() {
        assertEquals(1_000, DiscordPumpPolicy.idleIntervalMs(foreground = false))
    }

    @Test
    fun `the busy cadence is the old fast pump, and idle is never faster`() {
        assertEquals(10, DiscordPumpPolicy.BUSY_MS)
        assertTrue(DiscordPumpPolicy.FOREGROUND_IDLE_MS >= DiscordPumpPolicy.BUSY_MS)
    }
}
