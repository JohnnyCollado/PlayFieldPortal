package com.playfieldportal.studio

import java.util.prefs.Preferences

/**
 * Which wallpaper Fit the Background section starts on. A baked wallpaper is exactly its preset's
 * size, so the theme's own wallpaper names its fit (anything else was kept at its original size);
 * with no wallpaper, the author's last choice; HD only on a fresh install.
 */
object WallpaperFit {

    fun initial(wallpaperSize: Pair<Int, Int>?, remembered: WallpaperPreset?): WallpaperPreset {
        if (wallpaperSize != null) {
            val (w, h) = wallpaperSize
            return WallpaperPreset.entries.firstOrNull { it.width == w && it.height == h } ?: WallpaperPreset.ORIGINAL
        }
        return remembered ?: WallpaperPreset.HD
    }
}

/**
 * The author's last Fit, remembered per Studio install through [Preferences] (a unique node in
 * tests), like [PreviewAdjustStore]. An authoring preference, never theme content.
 */
class WallpaperFitStore(private val prefs: Preferences = Preferences.userRoot().node(NODE)) {

    val remembered: WallpaperPreset?
        get() = prefs.get(KEY_FIT, null)?.let { name -> WallpaperPreset.entries.firstOrNull { it.name == name } }

    fun remember(fit: WallpaperPreset) {
        runCatching {
            prefs.put(KEY_FIT, fit.name)
            prefs.flush()
        }
    }

    private companion object {
        const val NODE = "com/playfieldportal/studio/wallpaper-fit"
        const val KEY_FIT = "fit"
    }
}
