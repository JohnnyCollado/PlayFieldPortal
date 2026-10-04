package com.playfieldportal.feature.xmb.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The animated wave is a slow drift, so it repaints at ~30 fps rather than the panel's full rate
 * (60-144 Hz). Its clock is wall time, so a capped frame rate never changes how fast it moves.
 */
class WavePacingTest {

    @Test
    fun `the wave is capped at about 30 frames a second`() {
        assertTrue(WAVE_FRAME_INTERVAL_MS in 30L..34L)
    }

    @Test
    fun `wave time is seconds since the first frame, scaled by speed`() {
        assertEquals(0f, waveTimeSeconds(frameMs = 5_000, startMs = 5_000, speed = 1f), 0.0001f)
        assertEquals(2f, waveTimeSeconds(frameMs = 7_000, startMs = 5_000, speed = 1f), 0.0001f)
        assertEquals(1f, waveTimeSeconds(frameMs = 7_000, startMs = 5_000, speed = 0.5f), 0.0001f)
    }
}
