package com.playfieldportal.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Display ▸ Focus glow: the light round the focused icon, drawn in the icon's own shape. Off, Steady
 * or Pulsing. The owner's rules (21 Nov 2026): the pulse is slow and gentle, anyone can make it
 * steady or turn it off, and it never pulses while Animated Images is Static. (Reduced was first
 * ruled out too, then allowed the same day: Reduced means the focused item moves.)
 */
class FocusGlowTest {

    @Test
    fun `nothing stored, or something unknown, is steady`() {
        assertEquals(FocusGlowMode.STEADY, FocusGlowMode.fromStored(null))
        assertEquals(FocusGlowMode.STEADY, FocusGlowMode.fromStored("SPARKLE"))
        assertEquals(FocusGlowMode.PULSING, FocusGlowMode.fromStored("PULSING"))
        assertEquals(FocusGlowMode.OFF, FocusGlowMode.fromStored("OFF"))
    }

    @Test
    fun `pulsing plays under animated and reduced, and holds still under static or when motion is not allowed`() {
        fun shown(motion: ImageMotion, allowed: Boolean = true) =
            FocusGlowMode.PULSING.shown(motion, motionAllowed = allowed)
        assertEquals(FocusGlowMode.PULSING, shown(ImageMotion.ANIMATED))
        // Reduced means only the focused item moves, and the glow is on the focused item.
        assertEquals(FocusGlowMode.PULSING, shown(ImageMotion.REDUCED))
        assertEquals(FocusGlowMode.STEADY, shown(ImageMotion.REDUCED, allowed = false))
        assertEquals(FocusGlowMode.STEADY, shown(ImageMotion.STATIC))
        // Battery saver, a blocking overlay, system animations off.
        assertEquals(FocusGlowMode.STEADY, shown(ImageMotion.ANIMATED, allowed = false))
    }

    @Test
    fun `steady and off are never changed by the motion settings`() {
        for (motion in ImageMotion.entries) for (allowed in listOf(true, false)) {
            assertEquals(FocusGlowMode.STEADY, FocusGlowMode.STEADY.shown(motion, allowed))
            assertEquals(FocusGlowMode.OFF, FocusGlowMode.OFF.shown(motion, allowed))
        }
    }

    @Test
    fun `the pulse is slow`() {
        // One beat every three seconds or slower: far under three flashes a second.
        assertTrue(FocusGlow.PULSE_MS >= 3000)
    }

    @Test
    fun `the pulse never goes dark and never exceeds the steady glow`() {
        for (i in 0..100) {
            val level = FocusGlow.pulseLevel(i / 100f)
            assertTrue(level >= FocusGlow.PULSE_FLOOR - 0.0001f)
            assertTrue(level <= 1.0001f)
        }
    }

    @Test
    fun `it swells to full then fades for longer than it rose`() {
        assertEquals(FocusGlow.PULSE_FLOOR, FocusGlow.pulseLevel(0f), 0.001f)
        assertEquals(1f, FocusGlow.pulseLevel(FocusGlow.PULSE_PEAK_AT), 0.001f)
        assertEquals(FocusGlow.PULSE_FLOOR, FocusGlow.pulseLevel(1f), 0.001f)
        assertTrue(FocusGlow.PULSE_PEAK_AT < 0.5f)
    }

    @Test
    fun `the pulse has no sudden steps`() {
        var previous = FocusGlow.pulseLevel(0f)
        for (i in 1..300) {
            val level = FocusGlow.pulseLevel(i / 300f)
            assertTrue(kotlin.math.abs(level - previous) < 0.02f)
            previous = level
        }
    }

    @Test
    fun `it loops without a jump`() {
        assertEquals(FocusGlow.pulseLevel(0f), FocusGlow.pulseLevel(1f), 0.001f)
        assertEquals(FocusGlow.pulseLevel(0.25f), FocusGlow.pulseLevel(1.25f), 0.001f)
    }

    @Test
    fun `the glow is sized from the smaller side of the icon`() {
        // 216 px category icon: the radii the approved mockup used.
        assertEquals(18.6f, FocusGlow.softRadiusPx(216f, 216f), 0.1f)
        assertEquals(5f, FocusGlow.tightRadiusPx(216f, 216f), 0.1f)
        // A wide game tile is sized from its height, not its width.
        assertEquals(FocusGlow.softRadiusPx(210f, 210f), FocusGlow.softRadiusPx(378f, 210f), 0.001f)
    }
}
