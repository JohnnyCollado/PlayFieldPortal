package com.playfieldportal.core.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Display ▸ Background blur: one strength from 0 (off) to 1, where 1 is the strongest blur the app
 * draws behind a prompt or the options panel. The owner set that ceiling on 21 Nov 2026 as the blur
 * first seen on the device; the slider can only make it weaker.
 */
class BackdropBlurTest {

    @Test
    fun `full strength is the ceiling radius`() {
        assertEquals(BackdropBlur.MAX_RADIUS_DP, BackdropBlur.radiusDp(1f), 0.001f)
    }

    @Test
    fun `zero strength is no blur`() {
        assertEquals(0f, BackdropBlur.radiusDp(0f), 0.001f)
    }

    @Test
    fun `the radius scales in a straight line`() {
        assertEquals(BackdropBlur.MAX_RADIUS_DP / 2f, BackdropBlur.radiusDp(0.5f), 0.001f)
    }

    @Test
    fun `a stored value outside the range is pulled back into it`() {
        assertEquals(1f, BackdropBlur.coerce(3f), 0.001f)
        assertEquals(0f, BackdropBlur.coerce(-1f), 0.001f)
        assertEquals(BackdropBlur.MAX_RADIUS_DP, BackdropBlur.radiusDp(3f), 0.001f)
    }

    @Test
    fun `nothing stored means the default, which is weaker than the ceiling`() {
        assertEquals(BackdropBlur.DEFAULT_STRENGTH, BackdropBlur.coerce(null), 0.001f)
        assert(BackdropBlur.DEFAULT_STRENGTH < 1f)
        assert(BackdropBlur.DEFAULT_STRENGTH > 0f)
    }

    @Test
    fun `the slider moves in ten percent steps`() {
        assertEquals(0.3f, BackdropBlur.snap(0.34f), 0.001f)
        assertEquals(0.4f, BackdropBlur.snap(0.36f), 0.001f)
        assertEquals(1f, BackdropBlur.snap(0.97f), 0.001f)
    }
}
