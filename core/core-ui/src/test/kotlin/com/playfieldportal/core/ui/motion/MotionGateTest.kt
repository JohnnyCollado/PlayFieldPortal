package com.playfieldportal.core.ui.motion

import com.playfieldportal.core.domain.model.ImageMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate is what an animated drawable asks "may I play?". It starts closed until the image is
 * known to be on screen, re-decides on every input, and only tells its drawables when the answer
 * actually changes — so scrolling (which re-reports position constantly) never churns start/stop.
 */
class MotionGateTest {

    private class Recorder : MotionGate.Listener {
        val calls = mutableListOf<Boolean>()
        override fun onAnimateChanged(animate: Boolean) { calls += animate }
    }

    @Test
    fun `a new gate is closed until its image is reported on screen`() {
        val gate = MotionGate()
        gate.update(ImageMotion.ANIMATED, focused = true, allowed = true)
        assertFalse(gate.animate)

        gate.onScreen = true

        assertTrue(gate.animate)
    }

    @Test
    fun `Reduced opens only for the focused image`() {
        val gate = MotionGate().apply { onScreen = true }
        gate.update(ImageMotion.REDUCED, focused = false, allowed = true)
        assertFalse(gate.animate)

        gate.update(ImageMotion.REDUCED, focused = true, allowed = true)
        assertTrue(gate.animate)
    }

    @Test
    fun `listeners hear only real changes`() {
        val gate = MotionGate()
        val recorder = Recorder()
        gate.addListener(recorder)

        gate.update(ImageMotion.ANIMATED, focused = true, allowed = true)
        gate.onScreen = true
        gate.onScreen = true           // scrolling re-reports the same position state
        gate.update(ImageMotion.ANIMATED, focused = false, allowed = true)   // still plays under Animated
        gate.onScreen = false

        assertEquals(listOf(true, false), recorder.calls)
    }

    @Test
    fun `a removed listener hears nothing more`() {
        val gate = MotionGate().apply { update(ImageMotion.ANIMATED, focused = true, allowed = true) }
        val recorder = Recorder()
        gate.addListener(recorder)
        gate.removeListener(recorder)

        gate.onScreen = true

        assertTrue(recorder.calls.isEmpty())
    }

    @Test
    fun `the shared gate for unmanaged images plays unless Static or not allowed`() {
        val gate = MotionGate.unmanaged()
        gate.update(ImageMotion.REDUCED, focused = true, allowed = true)
        assertTrue("an image with no focus of its own counts as focused", gate.animate)

        gate.update(ImageMotion.STATIC, focused = true, allowed = true)
        assertFalse(gate.animate)

        gate.update(ImageMotion.ANIMATED, focused = true, allowed = false)
        assertFalse(gate.animate)
    }
}
