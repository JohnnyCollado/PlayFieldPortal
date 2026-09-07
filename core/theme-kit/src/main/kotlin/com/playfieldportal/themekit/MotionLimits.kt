package com.playfieldportal.themekit

/**
 * Import gate for user-picked motion wallpapers. Playback discipline cannot rescue a file that
 * should never have been accepted, so the gate runs BEFORE anything is copied, and every
 * rejection names its reason.
 *
 * Every number here is a judgment call rather than a hardware fact — which is why they live in
 * one object the tests point at: tuning a limit later is a one-file change.
 *
 * Lives in theme-kit (pure JVM), not core-ui, because BOTH sides of the feature need the same
 * numbers: the launcher's Android importer probes with MediaMetadataRetriever, and the desktop
 * Theme Studio validates the GIF it transcodes against exactly these caps before embedding it.
 * Two copies would drift, and the drift would show up as a Studio-authored theme the launcher
 * silently refuses.
 *
 * Tested by MotionLimitsTest, including the boundary at each cap.
 */
object MotionLimits {

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
     * Every extension [mimeForExtension] knows — the canonical list the drift tests iterate.
     * Adding a key to the mappings without adding it here means those tests silently stop
     * covering it, so an unknown-container regression would land exactly where they look.
     */
    val knownExtensions = setOf("mp4", "m4v", "webm", "gif", "webp")

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

    /**
     * File extension (no dot, any case) to the MIME [validate] expects, or null when the
     * extension is not a motion container at all.
     *
     * The launcher never needs this — Android's content resolver reports a real MIME for a picked
     * Uri. The desktop Theme Studio has only a filename, and validating there is not optional:
     * `PfpThemeStore.apply()` installs a bundle's motion entry WITHOUT re-running [validate], so
     * for a Studio-authored theme this gate is the only one there is.
     *
     * Keys are deliberately the same set as `PfpThemeCodec.MOTION_EXTENSIONS` plus the aliases
     * that are the same container under another name, so an extension that survives this mapping
     * is one the bundle writer will actually store.
     */
    fun mimeForExtension(extension: String): String? = when (extension.lowercase()) {
        // m4v is an MP4 container; both are written into the bundle as "mp4".
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        else -> null
    }

    /**
     * The extension a file of [extension] is stored under inside a theme bundle. Collapses the
     * aliases: `PfpThemeCodec` accepts only mp4/webm/gif, and silently drops a motion entry whose
     * extension is outside that set, so `motion.m4v` would vanish from the zip without a word.
     */
    fun bundleExtensionFor(extension: String): String? = when (extension.lowercase()) {
        "mp4", "m4v" -> "mp4"
        "webm" -> "webm"
        "gif" -> "gif"
        else -> null
    }
}
