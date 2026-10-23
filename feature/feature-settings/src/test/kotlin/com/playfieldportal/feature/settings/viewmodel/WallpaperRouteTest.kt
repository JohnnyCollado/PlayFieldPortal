package com.playfieldportal.feature.settings.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which wallpaper path a picked file takes.
 *
 * Survey U17 (docs/audits/codebase-survey-2026-10-22.md): every image/webp went to the motion path,
 * so a plain WebP photo was announced as "Motion wallpaper applied" and got the motion rows.
 */
class WallpaperRouteTest {

    private fun bytes(vararg parts: Any): ByteArray = parts.flatMap { p ->
        when (p) {
            is String -> p.toByteArray(Charsets.US_ASCII).toList()
            is Int -> listOf(p.toByte())
            else -> error("unsupported")
        }
    }.toByteArray()

    private fun webp(flags: Int) = bytes("RIFF", 0, 0, 0, 0, "WEBP", "VP8X", 10, 0, 0, 0, flags, 0, 0, 0)

    @Test
    fun `a still WebP takes the still path`() {
        assertFalse(isMotionWallpaper("image/webp", webp(flags = 0)))
        assertFalse(isMotionWallpaper("image/webp", bytes("RIFF", 0, 0, 0, 0, "WEBP", "VP8 ")))
    }

    @Test
    fun `an animated WebP, a GIF and video take the motion path`() {
        assertTrue(isMotionWallpaper("image/webp", webp(flags = 0x02)))
        assertTrue(isMotionWallpaper("image/gif", bytes("GIF89a")))
        assertTrue(isMotionWallpaper("video/mp4", ByteArray(0)))
    }

    @Test
    fun `a still image type is never motion`() {
        assertFalse(isMotionWallpaper("image/png", ByteArray(0)))
        assertFalse(isMotionWallpaper(null, ByteArray(0)))
    }
}
