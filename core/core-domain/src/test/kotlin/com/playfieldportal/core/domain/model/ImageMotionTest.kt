package com.playfieldportal.core.domain.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins when an animated image may play. Off-screen and not-allowed (PFP in the background, battery
 * saver, a blocking overlay) always hold the first frame, whatever the setting — that is the
 * "draw distance" that keeps Animated from costing battery for images nobody can see.
 */
class ImageMotionTest {

    @Test
    fun `off-screen never animates, whatever the mode`() {
        for (mode in ImageMotion.entries) {
            assertFalse(mode.shouldAnimate(onScreen = false, focused = true, allowed = true), "$mode")
        }
    }

    @Test
    fun `not allowed never animates, whatever the mode`() {
        for (mode in ImageMotion.entries) {
            assertFalse(mode.shouldAnimate(onScreen = true, focused = true, allowed = false), "$mode")
        }
    }

    @Test
    fun `Animated plays every on-screen image`() {
        assertTrue(ImageMotion.ANIMATED.shouldAnimate(onScreen = true, focused = false, allowed = true))
        assertTrue(ImageMotion.ANIMATED.shouldAnimate(onScreen = true, focused = true, allowed = true))
    }

    @Test
    fun `Reduced plays only the focused on-screen image`() {
        assertTrue(ImageMotion.REDUCED.shouldAnimate(onScreen = true, focused = true, allowed = true))
        assertFalse(ImageMotion.REDUCED.shouldAnimate(onScreen = true, focused = false, allowed = true))
    }

    @Test
    fun `Static never plays`() {
        assertFalse(ImageMotion.STATIC.shouldAnimate(onScreen = true, focused = true, allowed = true))
    }

    @Test
    fun `defaults to Reduced and reads back from its stored name`() {
        assertEquals(ImageMotion.REDUCED, ImageMotion.DEFAULT)
        assertEquals(ImageMotion.STATIC, ImageMotion.fromName("STATIC"))
        assertNull(ImageMotion.fromName("nonsense"))
    }

    @Test
    fun `labels read as the setting shows them`() {
        assertEquals(listOf("Animated", "Reduced", "Static"), ImageMotion.entries.map { it.label })
    }
}
