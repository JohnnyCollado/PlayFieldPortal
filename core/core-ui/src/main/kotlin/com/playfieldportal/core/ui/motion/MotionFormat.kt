package com.playfieldportal.core.ui.motion

/**
 * Which decoder a stored motion file needs. The extension is authoritative: the importer names
 * every file `wallpaper_<stamp>.<ext>` from the validated MIME (DisplaySettingsViewModel), so the
 * suffix IS the MIME by construction and no probe is needed at render time.
 *
 * The caps and the import gate itself no longer live here — they moved to
 * [com.playfieldportal.themekit.MotionLimits] so the desktop Theme Studio, which authors motion
 * wallpapers of its own, validates against the same numbers instead of a second copy that drifts.
 * What remains is the part that is genuinely render-side and Android-only.
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
