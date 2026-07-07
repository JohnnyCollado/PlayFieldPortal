package com.playfieldportal.themekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WallpaperMetricsTest {

    private fun image(width: Int = 480, height: Int = 272, argbAt: (x: Int, y: Int) -> Int) =
        BmpImage(width, height, IntArray(width * height) { i -> argbAt(i % width, i / width) })

    @Test
    fun `flat image is quiet`() {
        val flat = image { _, _ -> 0xFF6688AA.toInt() }
        assertEquals(0f, WallpaperMetrics.busyness(flat))
        assertFalse(WallpaperMetrics.isBusy(flat))
    }

    @Test
    fun `one-pixel checkerboard is busy`() {
        val checker = image { x, y -> if ((x + y) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        assertTrue(WallpaperMetrics.busyness(checker) > 0.5f)
        assertTrue(WallpaperMetrics.isBusy(checker))
    }

    @Test
    fun `soft vertical gradient is quiet`() {
        // Sony-style soft wallpaper: luminance drifts gently top to bottom.
        val soft = image { _, y ->
            val v = (80 + (y * 100 / 272)).coerceIn(0, 255)
            0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        assertTrue(WallpaperMetrics.busyness(soft) < WallpaperMetrics.BUSY_THRESHOLD)
        assertFalse(WallpaperMetrics.isBusy(soft))
    }

    @Test
    fun `luminance endpoints`() {
        assertEquals(0f, WallpaperMetrics.luminance(0xFF000000.toInt()))
        assertEquals(1f, WallpaperMetrics.luminance(0xFFFFFFFF.toInt()), 0.001f)
        // Pure green is the brightest primary under Rec.601.
        assertTrue(
            WallpaperMetrics.luminance(0xFF00FF00.toInt()) >
                WallpaperMetrics.luminance(0xFFFF0000.toInt()),
        )
    }
}
