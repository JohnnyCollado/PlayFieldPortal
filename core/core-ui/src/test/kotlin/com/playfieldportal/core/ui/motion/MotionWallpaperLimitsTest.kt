package com.playfieldportal.core.ui.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the import gate for user-picked motion wallpapers. The single largest risk in the feature
 * is a user picking a 4K/60 200 MB clip and concluding the launcher is broken, so every rejection
 * must name its reason, and the boundary at each cap must land on the accepting side exactly at
 * the limit.
 */
class MotionWallpaperLimitsTest {

    private val ok = MotionWallpaperLimits.Probe(
        mime = "video/mp4",
        width = 1920,
        height = 1080,
        durationMs = 60_000L,
        bytes = 60L * 1024 * 1024,
    )

    @Test
    fun `a 1080p 60s 60MB mp4 exactly at every cap is accepted`() {
        assertNull(MotionWallpaperLimits.validate(ok))
    }

    @Test
    fun `each supported mime is accepted`() {
        listOf("video/mp4", "video/webm", "image/gif", "image/webp").forEach { mime ->
            assertNull("expected $mime accepted", MotionWallpaperLimits.validate(ok.copy(mime = mime)))
        }
    }

    @Test
    fun `unsupported mime names the accepted formats`() {
        val error = MotionWallpaperLimits.validate(ok.copy(mime = "video/x-msvideo"))
        assertEquals("Unsupported format — use MP4, WebM, or GIF", error)
        // Static-image-only formats are rejected too — a still belongs to the plain wallpaper path.
        assertEquals(
            "Unsupported format — use MP4, WebM, or GIF",
            MotionWallpaperLimits.validate(ok.copy(mime = "image/png")),
        )
    }

    @Test
    fun `missing mime is rejected`() {
        val error = MotionWallpaperLimits.validate(ok.copy(mime = null))
        assertEquals("Unsupported format — use MP4, WebM, or GIF", error)
    }

    @Test
    fun `resolution over 1080p is rejected`() {
        assertEquals("Video is too large — 1080p or smaller", MotionWallpaperLimits.validate(ok.copy(width = 1921, height = 1080)))
        assertEquals("Video is too large — 1080p or smaller", MotionWallpaperLimits.validate(ok.copy(width = 1920, height = 1081)))
        // 4K, the most likely accidental pick.
        assertEquals("Video is too large — 1080p or smaller", MotionWallpaperLimits.validate(ok.copy(width = 3840, height = 2160)))
    }

    @Test
    fun `portrait video checks its long edge against the cap`() {
        assertNull(MotionWallpaperLimits.validate(ok.copy(width = 1080, height = 1920)))
        assertEquals(
            "Video is too large — 1080p or smaller",
            MotionWallpaperLimits.validate(ok.copy(width = 1081, height = 1920)),
        )
    }

    @Test
    fun `duration boundary - 60_000ms accepted, 60_001 rejected`() {
        assertNull(MotionWallpaperLimits.validate(ok.copy(durationMs = 60_000L)))
        assertEquals(
            "Clip is too long — 60 seconds or less",
            MotionWallpaperLimits.validate(ok.copy(durationMs = 60_001L)),
        )
    }

    @Test
    fun `file size boundary - exactly 60MB accepted, one byte over rejected`() {
        assertNull(MotionWallpaperLimits.validate(ok.copy(bytes = 60L * 1024 * 1024)))
        assertEquals(
            "File is too large — under 60 MB",
            MotionWallpaperLimits.validate(ok.copy(bytes = 60L * 1024 * 1024 + 1)),
        )
    }

    @Test
    fun `oversized GIF is rejected with the video-too-large message`() {
        // GIFs decode on the CPU holding every frame's bitmap — the resolution cap matters more
        // for them than for hardware-decoded video, but the message stays uniform.
        val error = MotionWallpaperLimits.validate(ok.copy(mime = "image/gif", width = 2560, height = 1440))
        assertEquals("Video is too large — 1080p or smaller", error)
    }

    @Test
    fun `degenerate zero-dimension probe is rejected`() {
        assertEquals(
            "Video is too large — 1080p or smaller",
            MotionWallpaperLimits.validate(ok.copy(width = 0, height = 1080)),
        )
    }
}
