package com.playfieldportal.core.ui.icons

/**
 * Import gate for user-picked custom XMB icons, mirroring MotionLimits: the gate runs
 * BEFORE anything is copied, and every rejection names its reason. Every number is a judgment
 * call living in one object so tuning later is a one-file change.
 *
 * The still/animated asymmetry is deliberate: oversized STILLS are downscaled by
 * SafeMedia.decodeFileCapped(targetDimension = [MAX_DIMENSION]) at import, never rejected;
 * oversized GIFs are rejected with a message — re-encoding a GIF is out of scope.
 *
 * Tested by CustomIconLimitsTest, including the boundary at each cap.
 */
object CustomIconLimits {

    // PNG, JPEG, WEBP, BMP, HEIF stills plus GIF animation. Anything the stock BitmapFactory
    // (and, for GIF, the bundled Coil AnimatedImageDecoder) can read.
    val SUPPORTED_MIME = setOf(
        "image/png",
        "image/jpeg",
        "image/webp",
        "image/bmp",
        "image/heif",
        "image/gif",
    )

    /** The animated member of [SUPPORTED_MIME] — the only kind the per-frame checks apply to. */
    const val MIME_GIF = "image/gif"

    /** An icon, not a wallpaper. Matches PfpThemeCodec.MAX_ICON_BYTES (schema v3). */
    const val MAX_BYTES = 8L * 1024 * 1024

    /** Largest template is 256px; 512 gives headroom. Stills downscale to this; GIFs must fit. */
    const val MAX_DIMENSION = 512

    /** Bounds decode for a glyph drawn in an 82dp box. */
    const val MAX_FRAMES = 120

    /** A looping accent, not a video. */
    const val MAX_DURATION_MS = 10_000L

    /** Rejection strings, surfaced verbatim through the customizer's message channel. */
    const val MSG_UNSUPPORTED_FORMAT = "Unsupported format — use PNG, JPG, WEBP, BMP, HEIC, or GIF"
    const val MSG_TOO_LARGE_RESOLUTION = "Image is too large — 512px or smaller for a GIF"
    const val MSG_TOO_MANY_FRAMES = "GIF has too many frames — 120 or fewer"
    const val MSG_TOO_LONG = "GIF is too long — 10 seconds or less"
    const val MSG_TOO_LARGE_BYTES = "File is too large — under 8 MB"
    const val MSG_UNDECODABLE = "Couldn't read that image — try a different file"

    /**
     * The probed media facts, collected by BitmapFactory bounds (still) / the GIF header
     * probe (animated) at import time. [mime] may be null when the picker couldn't report
     * one; [frameCount]/[durationMs] may be null when the probe couldn't determine them
     * cheaply — those checks are simply skipped.
     */
    data class Probe(
        val mime: String?,
        val width: Int,
        val height: Int,
        val frameCount: Int?,
        val durationMs: Long?,
        val bytes: Long,
    )

    /**
     * Returns the rejection message for the first failed check, or null when the probe
     * passes. Order matters only for UX: the cheapest, most-likely-wrong checks run first.
     *
     * Dimension caps apply to ANIMATED probes only — oversized stills are downscaled by the
     * decode step rather than rejected, so rejecting them here would be redundant. Degenerate
     * (zero) dimensions are rejected outright for both kinds.
     */
    fun validate(probe: Probe): String? = when {
        probe.mime == null || probe.mime !in SUPPORTED_MIME -> MSG_UNSUPPORTED_FORMAT
        probe.width <= 0 || probe.height <= 0 -> MSG_UNDECODABLE
        probe.bytes > MAX_BYTES -> MSG_TOO_LARGE_BYTES
        probe.mime == MIME_GIF -> when {
            probe.width > MAX_DIMENSION || probe.height > MAX_DIMENSION -> MSG_TOO_LARGE_RESOLUTION
            probe.frameCount != null && probe.frameCount > MAX_FRAMES -> MSG_TOO_MANY_FRAMES
            probe.durationMs != null && probe.durationMs > MAX_DURATION_MS -> MSG_TOO_LONG
            else -> null
        }
        else -> null
    }
}
