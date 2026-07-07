package com.playfieldportal.themekit

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrossBandDetectorTest {

    private val bright = 0xFFB4B4B4.toInt() // luma ~0.70
    private val dark = 0xFF1A1A1A.toInt()   // luma ~0.10

    /** width x height image, [dark] where rows fall inside bandRows, else [bright]. */
    private fun bandImage(
        width: Int = 480,
        height: Int = 272,
        bandRows: IntRange?,
        bandColor: Int = dark,
        background: Int = bright,
    ): BmpImage {
        val argb = IntArray(width * height) { i ->
            val row = i / width
            if (bandRows != null && row in bandRows) bandColor else background
        }
        return BmpImage(width, height, argb)
    }

    @Test
    fun `uniform image yields null`() {
        assertNull(CrossBandDetector.detectBarTopFraction(bandImage(bandRows = null)))
    }

    @Test
    fun `dark band at quarter height is detected near its top edge`() {
        // Band from 0.25h to 0.40h on a 272-tall image: rows 68..108.
        val fraction = assertNotNull(
            CrossBandDetector.detectBarTopFraction(bandImage(bandRows = 68..108)),
        )
        assertTrue(fraction in 0.22f..0.28f, "expected ≈0.25, got $fraction")
    }

    @Test
    fun `band in the lower half is ignored`() {
        // 0.60h..0.75h — below the search region.
        assertNull(CrossBandDetector.detectBarTopFraction(bandImage(bandRows = 163..204)))
    }

    @Test
    fun `a thin line is not a band`() {
        assertNull(CrossBandDetector.detectBarTopFraction(bandImage(bandRows = 68..70)))
    }

    @Test
    fun `low-contrast band is rejected`() {
        val slightlyDark = 0xFF9E9E9E.toInt() // luma ~0.62 vs 0.70 background
        assertNull(
            CrossBandDetector.detectBarTopFraction(
                bandImage(bandRows = 68..108, bandColor = slightlyDark),
            ),
        )
    }

    @Test
    fun `dark-from-the-top image has no top edge and is rejected`() {
        // Dark from row 0 down to 40% — a night sky, not a band.
        assertNull(CrossBandDetector.detectBarTopFraction(bandImage(bandRows = 0..108)))
    }

    @Test
    fun `tiny images yield null`() {
        assertNull(CrossBandDetector.detectBarTopFraction(bandImage(width = 16, height = 16, bandRows = 4..8)))
    }
}
