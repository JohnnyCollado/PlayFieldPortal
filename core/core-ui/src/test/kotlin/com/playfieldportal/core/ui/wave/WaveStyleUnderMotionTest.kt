package com.playfieldportal.core.ui.wave

import com.playfieldportal.core.domain.model.ImageMotion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Display ▸ Motion sits above the background's own style. Static holds the wave and a motion
 * wallpaper still whatever their style says, keeping the dim-or-full look; Animated and Reduced
 * leave the style as the user (or their theme) set it.
 */
class WaveStyleUnderMotionTest {

    @Test
    fun `static motion holds every style still and keeps its look`() {
        assertEquals(WaveStyle.STATIC, WaveStyle.ANIMATED.under(ImageMotion.STATIC))
        assertEquals(WaveStyle.STATIC, WaveStyle.STATIC.under(ImageMotion.STATIC))
        assertEquals(WaveStyle.REDUCED_STATIC, WaveStyle.REDUCED.under(ImageMotion.STATIC))
        assertEquals(WaveStyle.REDUCED_STATIC, WaveStyle.REDUCED_STATIC.under(ImageMotion.STATIC))
        for (style in WaveStyle.entries) assertFalse(style.under(ImageMotion.STATIC).animated)
    }

    @Test
    fun `animated and reduced motion leave the style alone`() {
        for (style in WaveStyle.entries) {
            assertEquals(style, style.under(ImageMotion.ANIMATED))
            assertEquals(style, style.under(ImageMotion.REDUCED))
        }
    }
}
