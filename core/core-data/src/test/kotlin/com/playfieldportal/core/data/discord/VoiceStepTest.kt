package com.playfieldportal.core.data.discord

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Cycling a voice level moves to the next step above the current value.
 *
 * Survey L19 (docs/audits/codebase-survey-2026-10-22.md): the cycle found the first step at or
 * above the value and then moved one further, so 30 (settable directly) jumped to 75, skipping 50.
 */
class VoiceStepTest {

    private val steps = listOf(0, 25, 50, 75, 100)

    @Test
    fun `an on-grid value moves to the next step and wraps at the top`() {
        assertEquals(50, nextVoiceStep(25, steps))
        assertEquals(0, nextVoiceStep(100, steps))
    }

    @Test
    fun `an off-grid value moves to the next step above it, skipping none`() {
        assertEquals(50, nextVoiceStep(30, steps))
        assertEquals(25, nextVoiceStep(1, steps))
        assertEquals(0, nextVoiceStep(130, steps))
    }
}
