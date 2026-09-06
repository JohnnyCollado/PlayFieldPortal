package com.playfieldportal.core.ui.motion

/**
 * Import gate for user-picked motion wallpapers. Playback discipline cannot rescue a file that
 * should never have been accepted, so the gate runs BEFORE anything is copied, and every
 * rejection names its reason.
 *
 * Every number here is a judgment call rather than a hardware fact — which is why they live in
 * one object the tests point at: tuning a limit later is a one-file change. (Transcoding
 * oversized picks instead of rejecting them is deliberately out of scope for the first pass.)
 *
 * Tested by MotionWallpaperLimitsTest, including the boundary at each cap.
 */
object MotionWallpaperLimits {

    // Accepted containers. GIF/animated WebP ride Coil's animated-image decoder; MP4/WebM go to
    // ExoPlayer. Still-image formats belong to the plain wallpaper path, not this gate.
    val SUPPORTED_MIME = setOf("video/mp4", "video/webm", "image/gif", "image/webp")

    const val MAX_WIDTH = 1920
    const val MAX_HEIGHT = 1080

    const val MAX_DURATION_MS = 60_000L

    const val MAX_BYTES = 60L * 1024 * 1024

    /** Advisory only — accepted; playback is simply slowed under REDUCED (no frame-rate knob). */
    const val ADVISORY_MAX_FPS = 30

    /** Rejection strings, surfaced verbatim through the wallpaper importer's message channel. */
    const val MSG_UNSUPPORTED_FORMAT = "Unsupported format — use MP4, WebM, or GIF"
    const val MSG_TOO_LARGE_RESOLUTION = "Video is too large — 1080p or smaller"
    const val MSG_TOO_LONG = "Clip is too long — 60 seconds or less"
    const val MSG_TOO_LARGE_BYTES = "File is too large — under 60 MB"
    const val MSG_UNDECODABLE = "Couldn't read that video — try a different file"

    /**
     * The probed media facts, collected by MediaMetadataRetriever at import time. [mime] may be
     * null when the picker couldn't report one.
     */
    data class Probe(
        val mime: String?,
        val width: Int,
        val height: Int,
        val durationMs: Long,
        val bytes: Long,
    )

    /**
     * Returns the rejection message for the first failed check, or null when the probe passes.
     * Order matters only for UX: the cheapest, most-likely-wrong checks run first.
     *
     * Resolution is checked long-edge vs short-edge, not per-axis: a portrait 1080×1920 clip is
     * "1080p" just as much as a landscape 1920×1080 one, and the background center-crops
     * either way. Degenerate (zero) dimensions are rejected outright.
     */
    fun validate(probe: Probe): String? = when {
        probe.mime == null || probe.mime !in SUPPORTED_MIME -> MSG_UNSUPPORTED_FORMAT
        probe.width <= 0 || probe.height <= 0 ||
            maxOf(probe.width, probe.height) > MAX_WIDTH ||
            minOf(probe.width, probe.height) > MAX_HEIGHT -> MSG_TOO_LARGE_RESOLUTION
        probe.durationMs > MAX_DURATION_MS -> MSG_TOO_LONG
        probe.bytes > MAX_BYTES -> MSG_TOO_LARGE_BYTES
        else -> null
    }
}

/**
 * Which decoder a stored motion file needs. The extension is authoritative: the importer names
 * every file `wallpaper_<stamp>.<ext>` from the validated MIME (DisplaySettingsViewModel), so the
 * suffix IS the MIME by construction and no probe is needed at render time.
 */
enum class MotionFormat { VIDEO, ANIMATED_IMAGE }

/**
 * Classifies a stored motion path by suffix. VIDEO stays the else-branch deliberately: an
 * unexpected suffix goes to ExoPlayer, which fails loudly, rather than to Coil, which would fail
 * silently.
 */
fun formatOf(path: String): MotionFormat =
    if (path.endsWith(".gif", ignoreCase = true) || path.endsWith(".webp", ignoreCase = true)) {
        MotionFormat.ANIMATED_IMAGE
    } else {
        MotionFormat.VIDEO
    }
