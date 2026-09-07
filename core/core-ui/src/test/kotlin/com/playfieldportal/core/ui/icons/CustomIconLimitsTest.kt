package com.playfieldportal.core.ui.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the import gate for user-picked custom XMB icons, mirroring MotionLimitsTest:
 * every rejection names its reason, and the boundary at each cap lands on the accepting side
 * exactly at the limit.
 *
 * The still/animated asymmetry is deliberate and load-bearing: oversized STILLS are downscaled
 * at decode time (decodeFileCapped), never rejected, so only animated probes trip the
 * resolution cap here.
 */
class CustomIconLimitsTest {

    private val still = CustomIconLimits.Probe(
        mime = "image/png",
        width = 256,
        height = 256,
        frameCount = null,
        durationMs = null,
        bytes = 64L * 1024,
    )

    private val animated = CustomIconLimits.Probe(
        mime = "image/gif",
        width = 256,
        height = 256,
        frameCount = 24,
        durationMs = 2_000L,
        bytes = 512L * 1024,
    )

    // ── format ────────────────────────────────────────────────────────────────

    @Test
    fun `every supported mime is accepted`() {
        for (mime in CustomIconLimits.SUPPORTED_MIME) {
            assertNull("expected $mime accepted", CustomIconLimits.validate(animated.copy(mime = mime)))
        }
    }

    @Test
    fun `unsupported and missing mimes are rejected`() {
        assertEquals(CustomIconLimits.MSG_UNSUPPORTED_FORMAT, CustomIconLimits.validate(animated.copy(mime = "video/mp4")))
        assertEquals(CustomIconLimits.MSG_UNSUPPORTED_FORMAT, CustomIconLimits.validate(animated.copy(mime = null)))
    }

    // ── bytes (both kinds) ────────────────────────────────────────────────────

    @Test
    fun `file size boundary - exactly 8MB accepted, one byte over rejected`() {
        assertNull(CustomIconLimits.validate(still.copy(bytes = CustomIconLimits.MAX_BYTES)))
        assertEquals(
            CustomIconLimits.MSG_TOO_LARGE_BYTES,
            CustomIconLimits.validate(still.copy(bytes = CustomIconLimits.MAX_BYTES + 1)),
        )
        assertNull(CustomIconLimits.validate(animated.copy(bytes = CustomIconLimits.MAX_BYTES)))
        assertEquals(
            CustomIconLimits.MSG_TOO_LARGE_BYTES,
            CustomIconLimits.validate(animated.copy(bytes = CustomIconLimits.MAX_BYTES + 1)),
        )
    }

    // ── animated-only checks ──────────────────────────────────────────────────

    @Test
    fun `gif dimension boundary - exactly 512 accepted, 513 rejected`() {
        assertNull(CustomIconLimits.validate(animated.copy(width = 512, height = 512)))
        assertEquals(
            CustomIconLimits.MSG_TOO_LARGE_RESOLUTION,
            CustomIconLimits.validate(animated.copy(width = 513, height = 256)),
        )
        assertEquals(
            CustomIconLimits.MSG_TOO_LARGE_RESOLUTION,
            CustomIconLimits.validate(animated.copy(width = 256, height = 513)),
        )
    }

    @Test
    fun `oversized still dimensions are NOT rejected - decode downscales instead`() {
        assertNull(CustomIconLimits.validate(still.copy(width = 4000, height = 4000)))
    }

    @Test
    fun `gif frame count boundary - exactly 120 accepted, 121 rejected`() {
        assertNull(CustomIconLimits.validate(animated.copy(frameCount = CustomIconLimits.MAX_FRAMES)))
        assertEquals(
            CustomIconLimits.MSG_TOO_MANY_FRAMES,
            CustomIconLimits.validate(animated.copy(frameCount = CustomIconLimits.MAX_FRAMES + 1)),
        )
    }

    @Test
    fun `gif duration boundary - exactly 10s accepted, one ms over rejected`() {
        assertNull(CustomIconLimits.validate(animated.copy(durationMs = CustomIconLimits.MAX_DURATION_MS)))
        assertEquals(
            CustomIconLimits.MSG_TOO_LONG,
            CustomIconLimits.validate(animated.copy(durationMs = CustomIconLimits.MAX_DURATION_MS + 1)),
        )
    }

    @Test
    fun `unknown frame count or duration skips those checks`() {
        // The store cannot cheaply probe a GIF's frame count/duration pre-decode; the probe
        // fields are nullable and the checks degrade to the ones it does know.
        assertNull(CustomIconLimits.validate(animated.copy(frameCount = null, durationMs = null)))
    }

    @Test
    fun `degenerate zero-dimension probe is rejected`() {
        assertEquals(
            CustomIconLimits.MSG_UNDECODABLE,
            CustomIconLimits.validate(animated.copy(width = 0, height = 256)),
        )
    }
}
