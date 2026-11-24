package com.playfieldportal.core.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the glow takes its picture of the icon. A focused icon keeps looking for a few seconds, so box
 * art that finishes loading late still gets a glow in its own shape rather than its placeholder's.
 */
class FocusGlowCaptureTest {

    @Test
    fun `a focused icon is looked at again after late art has had time to load`() {
        val times = captureDelaysMs(active = true).runningReduce(Long::plus)
        assertTrue("last look at ${times.last()} ms", times.last() >= 8_000)
        assertEquals("the first look is still quick", 60L, times.first())
    }

    @Test
    fun `an icon prepared in the background keeps the short schedule`() {
        val times = captureDelaysMs(active = false).runningReduce(Long::plus)
        assertTrue("last look at ${times.last()} ms", times.last() <= 2_000)
    }
}
