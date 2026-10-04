package com.playfieldportal.core.ui.sound

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When the theme's ambience loop plays, holds still, or is released. Paused (a dialog-style app over
 * the launcher) holds the loop where it is; stopped (a game in front) releases the decoder.
 */
class AmbienceDecisionTest {

    private fun decide(
        assigned: Boolean = true,
        gain: Float = 0.5f,
        foreground: Boolean = true,
        hostPaused: Boolean = false,
        bootFinished: Boolean = true,
        held: Boolean = false,
    ) = AmbienceDecision.decide(assigned, gain, foreground, hostPaused, bootFinished, held)

    @Test
    fun `on screen with everything ready it plays`() {
        assertEquals(AmbienceDecision.PLAY, decide())
    }

    @Test
    fun `paused but still visible it holds, keeping its place`() {
        assertEquals(AmbienceDecision.HOLD, decide(hostPaused = true))
    }

    @Test
    fun `stopped it releases, whatever else is true`() {
        assertEquals(AmbienceDecision.RELEASE, decide(foreground = false))
        assertEquals(AmbienceDecision.RELEASE, decide(foreground = false, hostPaused = true))
    }

    @Test
    fun `nothing to play releases`() {
        assertEquals(AmbienceDecision.RELEASE, decide(assigned = false))
        assertEquals(AmbienceDecision.RELEASE, decide(gain = 0f))
        assertEquals(AmbienceDecision.RELEASE, decide(bootFinished = false))
        assertEquals(AmbienceDecision.RELEASE, decide(held = true))
    }
}
