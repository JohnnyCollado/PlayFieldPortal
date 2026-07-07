package com.playfieldportal.themekit

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the gradient anchors for known scheme colors. The launcher's XMB background and the
 * Theme Studio preview both derive from these values — if this test moves, one of them has
 * silently diverged from the other.
 */
class ColorCascadeTest {

    private fun anchors(wave: Long) = ColorCascade.lightBackgroundAnchors(wave)

    @Test
    fun `classic blue anchors match the launcher's tuned gradient`() {
        // 0xFF0055AA: top = channels * 0.62, bottom = channels blended 28% toward white.
        val (top, bottom) = anchors(0xFF0055AAL)
        assertEquals(0xFF003469L, top)
        assertEquals(0xFF4784C1L, bottom)
    }

    @Test
    fun `white and black stay clamped in range`() {
        val (whiteTop, whiteBottom) = anchors(0xFFFFFFFFL)
        assertEquals(0xFF9E9E9EL, whiteTop)      // 255 * 0.62 = 158.1 -> 158
        assertEquals(0xFFFFFFFFL, whiteBottom)   // already white

        val (blackTop, blackBottom) = anchors(0xFF000000L)
        assertEquals(0xFF000000L, blackTop)
        assertEquals(0xFF474747L, blackBottom)   // 0 + 255 * 0.28 = 71.4 -> 71
    }

    @Test
    fun `alpha is always forced opaque`() {
        val (top, bottom) = anchors(0x000055AAL) // hostile input: zero alpha
        assertEquals(0xFFL, (top shr 24) and 0xFFL)
        assertEquals(0xFFL, (bottom shr 24) and 0xFFL)
    }
}
