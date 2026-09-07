package com.playfieldportal.core.ui.motion

import com.playfieldportal.themekit.MotionLimits
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the render-time format classification ([formatOf]) that routes a
 * motion wallpaper between the ExoPlayer surface and the Coil animated-image surface. The
 * importer names files `wallpaper_<stamp>.<ext>` from the validated MIME, so the extension is
 * authoritative — these tests guard the mapping and its deliberate fallback.
 */
class MotionWallpaperFormatTest {

    @Test
    fun `gif and webp suffixes classify as animated image regardless of case`() {
        listOf("/data/wallpaper/wallpaper_1.gif", "/data/wallpaper/wallpaper_2.GIF").forEach {
            assertEquals(MotionFormat.ANIMATED_IMAGE, formatOf(it))
        }
        listOf("/data/wallpaper/wallpaper_3.webp", "/data/wallpaper/wallpaper_4.WebP").forEach {
            assertEquals(MotionFormat.ANIMATED_IMAGE, formatOf(it))
        }
    }

    @Test
    fun `video suffixes classify as video`() {
        listOf("/data/wallpaper/wallpaper_5.mp4", "/data/wallpaper/wallpaper_6.WEBM").forEach {
            assertEquals(MotionFormat.VIDEO, formatOf(it))
        }
    }

    @Test
    fun `unknown or absent extension falls back to video`() {
        // Deliberate fallback: an unexpected suffix goes to ExoPlayer, which fails loudly, rather
        // than to Coil, which would fail silently.
        assertEquals(MotionFormat.VIDEO, formatOf("/data/wallpaper/wallpaper_7.avi"))
        assertEquals(MotionFormat.VIDEO, formatOf("/data/wallpaper/wallpaper_8"))
    }

    @Test
    fun `gif appearing in a directory name does not classify the file`() {
        assertEquals(
            MotionFormat.VIDEO,
            formatOf("/data/wallpaper/my.gif.stuff/wallpaper_9.mp4"),
        )
    }

    @Test
    fun `every supported mime maps to its expected format`() {
        // Explicit, not computed: adding a MIME to SUPPORTED_MIME without teaching formatOf about
        // it must fail here (falling into the VIDEO fallback) rather than silently landing in
        // the ExoPlayer branch.
        val expected = mapOf(
            "video/mp4" to MotionFormat.VIDEO,
            "video/webm" to MotionFormat.VIDEO,
            "image/gif" to MotionFormat.ANIMATED_IMAGE,
            "image/webp" to MotionFormat.ANIMATED_IMAGE,
        )
        assertEquals(expected.keys, MotionLimits.SUPPORTED_MIME)
        expected.forEach { (mime, format) ->
            val ext = mime.substringAfter('/')
            assertEquals(
                "expected $mime (.$ext) to classify as $format",
                format,
                formatOf("/data/wallpaper/wallpaper_10.$ext"),
            )
        }
    }
}
