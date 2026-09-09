package com.playfieldportal.core.data.wallpaper

import android.graphics.BitmapFactory
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.themekit.BmpImage
import com.playfieldportal.themekit.WallpaperLuminanceMap
import java.io.File
import kotlin.math.max

/**
 * The Android side of [WallpaperLuminanceMap]: decodes a wallpaper file small and hands back the
 * stored form of its luminance survey, so the XMB can size text protection per label.
 *
 * The survey itself is pure JVM and lives in `theme-kit`; this object exists only because
 * `BitmapFactory` does not. It deliberately returns the **JSON string** rather than the map:
 * `core-data` depends on `theme-kit` with `implementation`, and keeping the parsed type out of the
 * public signature means callers that only *write* the key need no theme-kit import at all.
 * Readers (the XMB) parse with [WallpaperLuminanceMap.fromJson], which is also where staleness is
 * detected.
 *
 * See `docs/plans/text-legibility-font-color-plan.md`.
 */
object WallpaperLuminanceProbe {

    /**
     * The survey, as JSON, keyed by the wallpaper path it describes.
     *
     * One key, no companion stamp: wallpaper files are already uniquified per import (see
     * `DisplaySettingsViewModel.importStillWallpaper`) precisely so Coil's path-keyed cache
     * invalidates, and that same uniqueness makes the embedded source path a sufficient staleness
     * check.
     */
    val KEY_WALLPAPER_LUMA = stringPreferencesKey("display_wallpaper_luma")

    /**
     * Longest edge we decode to. The survey is 12x3 bands of averages — resolution beyond this
     * buys nothing, and the downscale is what keeps the whole probe in single-digit milliseconds.
     */
    const val MAX_EDGE = 256

    /**
     * Survey the wallpaper at [path], or `null` if it cannot be decoded.
     *
     * Blocking I/O and a bitmap decode: call it from `Dispatchers.IO`, and **before** opening the
     * DataStore transaction that stores the result — an `edit { }` transform may be re-run, and a
     * bitmap decode is not something to repeat under a lock.
     *
     * A `null` return is not an error worth surfacing. The wallpaper still applies; the XMB simply
     * falls back to its unconditioned protection until something recomputes the map.
     */
    fun survey(path: String): String? = runCatching {
        if (!File(path).isFile) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val decoded = BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply {
                // BitmapFactory rounds inSampleSize down to a power of two itself; computing the
                // ratio and letting it round is the same bounds-pass idiom probeMotionFile uses.
                inSampleSize = max(1, max(bounds.outWidth, bounds.outHeight) / MAX_EDGE)
            },
        ) ?: return null

        // Read everything off the bitmap before recycling it.
        val width = decoded.width
        val height = decoded.height
        val pixels = IntArray(width * height)
        decoded.getPixels(pixels, 0, width, 0, 0, width, height)
        decoded.recycle()
        if (width <= 0 || height <= 0) return null

        WallpaperLuminanceMap.compute(BmpImage(width, height, pixels), path).toJson()
    }.getOrNull()

    /**
     * Whether [json] is a readable survey **of the wallpaper at [path]**.
     *
     * The staleness check, kept here so callers need no theme-kit import to ask it. `false` means
     * "recompute", covering all three ways a stored survey goes bad: absent, unparseable, or
     * describing a different file (a re-homed path after an OS update, or a map restored from
     * another device's backup).
     */
    fun describes(json: String?, path: String): Boolean =
        json != null && WallpaperLuminanceMap.fromJson(json, path) != null

    /**
     * Store [json] under [KEY_WALLPAPER_LUMA], or remove the key when it is `null`.
     *
     * Removal rather than a stale value on failure: an absent map means "unknown, use the
     * conservative default", while a map describing the *previous* wallpaper would have the XMB
     * confidently sizing protection against the wrong picture.
     */
    fun MutablePreferences.setWallpaperLuma(json: String?) {
        if (json != null) this[KEY_WALLPAPER_LUMA] = json else this.remove(KEY_WALLPAPER_LUMA)
    }

    /** Drop the survey. Pair this with every site that clears the wallpaper itself. */
    fun MutablePreferences.clearWallpaperLuma() {
        this.remove(KEY_WALLPAPER_LUMA)
    }
}
