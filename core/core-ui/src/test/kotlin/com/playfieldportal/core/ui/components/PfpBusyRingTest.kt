package com.playfieldportal.core.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The busy ring steps through 15 discrete frames, one turn per ~900 ms. */
class PfpBusyRingTest {

    @Test
    fun `frame zero sits at the rest angle`() {
        assertEquals(0f, busyRingAngleForFrame(0), 0f)
    }

    @Test
    fun `each frame advances by a fifteenth of a turn`() {
        assertEquals(24f, busyRingAngleForFrame(1), 0.001f)
        assertEquals(336f, busyRingAngleForFrame(14), 0.001f)
    }

    @Test
    fun `frame numbers wrap and negatives fold back`() {
        assertEquals(busyRingAngleForFrame(0), busyRingAngleForFrame(BUSY_RING_FRAMES), 0f)
        assertEquals(busyRingAngleForFrame(14), busyRingAngleForFrame(-1), 0f)
    }

    @Test
    fun `elapsed time maps to a frame every 60 ms`() {
        assertEquals(0, busyRingFrameForElapsed(0L))
        assertEquals(0, busyRingFrameForElapsed(59L))
        assertEquals(1, busyRingFrameForElapsed(60L))
        assertEquals(14, busyRingFrameForElapsed(899L))
    }

    @Test
    fun `one full turn returns to frame zero`() {
        assertEquals(0, busyRingFrameForElapsed(BUSY_RING_TURN_MILLIS))
        assertEquals(1, busyRingFrameForElapsed(BUSY_RING_TURN_MILLIS + 60L))
    }

    @Test
    fun `negative elapsed time is clamped to the first frame`() {
        assertEquals(0, busyRingFrameForElapsed(-500L))
    }

    @Test
    fun `stroke grows with size`() {
        assertTrue(busyRingStrokeFraction() > 0f && busyRingStrokeFraction() < 0.5f)
    }
}
