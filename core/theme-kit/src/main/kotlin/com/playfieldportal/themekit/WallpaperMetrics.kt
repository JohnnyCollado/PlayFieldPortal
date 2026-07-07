package com.playfieldportal.themekit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Legibility heuristics for wallpapers and icon colors. Sony's own theme wallpapers are
 * soft, low-contrast photos precisely so the XMB's white labels stay readable; these
 * metrics power the Studio's non-blocking hints toward the same, without ever refusing
 * an image.
 */
object WallpaperMetrics {

    /** Busyness above this reads as "labels may struggle" (soft gradients score ≲0.02). */
    const val BUSY_THRESHOLD = 0.055f

    /** Icon colors darker than this fight the wallpaper scrim / gradient. */
    const val DARK_ICON_LUMINANCE = 0.35f

    /**
     * Mean absolute luminance gradient over the label band — the region the item column's
     * text sits on (x 0.10..0.60, y 0.30..0.85) — sampled at ~[maxSamples] pixels.
     * Flat image → ~0; 1px checkerboard → ~1.
     */
    fun busyness(image: BmpImage, maxSamples: Int = 6000): Float {
        val width = image.width
        val height = image.height
        if (width < 4 || height < 4) return 0f

        val x0 = (width * 0.10f).toInt()
        val x1 = (width * 0.60f).toInt().coerceAtMost(width - 2)
        val y0 = (height * 0.30f).toInt()
        val y1 = (height * 0.85f).toInt().coerceAtMost(height - 2)
        if (x1 <= x0 || y1 <= y0) return 0f

        val regionPixels = (x1 - x0) * (y1 - y0)
        val stride = max(1, (sqrt(regionPixels / maxSamples.toFloat())).toInt())

        var sum = 0f
        var count = 0
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val here = luminance(image.argb[y * width + x])
                val right = luminance(image.argb[y * width + x + 1])
                val below = luminance(image.argb[(y + 1) * width + x])
                sum += (abs(here - right) + abs(here - below)) / 2f
                count++
                x += stride
            }
            y += stride
        }
        return if (count == 0) 0f else sum / count
    }

    fun isBusy(image: BmpImage): Boolean = busyness(image) > BUSY_THRESHOLD

    /** Rec.601 luma of an ARGB pixel, 0..1. */
    fun luminance(argb: Int): Float = CrossBandDetector.luminance(argb)
}
