package com.playfieldportal.themekit

/**
 * Derives a theme accent color from a wallpaper's dominant saturated hue.
 *
 * This is the heart of PTF import (docs/ptf-import-plan.md): the PSP only stores one of 12
 * preset theme colors (or none), so deriving from the wallpaper is both more faithful and
 * universal. Verified against Sony's example themes: cookies -> warm amber, classypink ->
 * petal pink (#FF72B1), Evangelion -> NERV red.
 */
object AccentDeriver {

    private const val HUE_BUCKETS = 24
    private const val MIN_SATURATION = 0.20f
    private const val MIN_VALUE = 0.30f

    // The winning bucket's most vivid pixel is normalized up to a usable UI accent —
    // wallpapers are often soft/pastel, but the accent drives wave/cursor/icon tints.
    private const val ACCENT_MIN_SATURATION = 0.55f
    private const val ACCENT_MIN_VALUE = 0.85f

    /**
     * Returns the accent as a packed ARGB int, or null when the image has no meaningfully
     * saturated pixels (grayscale/near-monochrome wallpapers) — callers fall back to a
     * default scheme color.
     *
     * @param maxSamples pixel budget; the image is stride-sampled down to roughly this count.
     */
    fun deriveAccent(image: BmpImage, maxSamples: Int = 6000): Int? {
        val total = image.argb.size
        if (total == 0) return null
        val stride = (total / maxSamples).coerceAtLeast(1)

        val counts = IntArray(HUE_BUCKETS)
        val bestVividness = FloatArray(HUE_BUCKETS)
        val bestPixel = IntArray(HUE_BUCKETS)

        var i = 0
        while (i < total) {
            val pixel = image.argb[i]
            val r = (pixel shr 16 and 0xFF) / 255f
            val g = (pixel shr 8 and 0xFF) / 255f
            val b = (pixel and 0xFF) / 255f

            val v = maxOf(r, g, b)
            val delta = v - minOf(r, g, b)
            val s = if (v == 0f) 0f else delta / v

            if (s > MIN_SATURATION && v > MIN_VALUE) {
                val h = hue(r, g, b, v, delta)
                val bucket = ((h * HUE_BUCKETS).toInt()).coerceIn(0, HUE_BUCKETS - 1)
                counts[bucket]++
                val vividness = s * v
                if (vividness > bestVividness[bucket]) {
                    bestVividness[bucket] = vividness
                    bestPixel[bucket] = pixel
                }
            }
            i += stride
        }

        val winner = counts.indices.maxBy { counts[it] }
        if (counts[winner] == 0) return null

        // Re-derive HSV from the winning bucket's most vivid representative, then boost
        // saturation/value floors so pastel sources still yield a strong accent.
        val pixel = bestPixel[winner]
        val r = (pixel shr 16 and 0xFF) / 255f
        val g = (pixel shr 8 and 0xFF) / 255f
        val b = (pixel and 0xFF) / 255f
        val v = maxOf(r, g, b)
        val delta = v - minOf(r, g, b)
        val s = if (v == 0f) 0f else delta / v
        val h = hue(r, g, b, v, delta)

        return hsvToArgb(h, s.coerceAtLeast(ACCENT_MIN_SATURATION), v.coerceAtLeast(ACCENT_MIN_VALUE))
    }

    /** Hue in [0, 1). Inputs are the r/g/b components with their precomputed max and delta. */
    private fun hue(r: Float, g: Float, b: Float, v: Float, delta: Float): Float {
        if (delta == 0f) return 0f
        val h = when (v) {
            r -> ((g - b) / delta) % 6f
            g -> (b - r) / delta + 2f
            else -> (r - g) / delta + 4f
        } / 6f
        return if (h < 0f) h + 1f else h
    }

    private fun hsvToArgb(h: Float, s: Float, v: Float): Int {
        val sector = (h * 6f)
        val i = sector.toInt() % 6
        val f = sector - sector.toInt()
        val p = v * (1f - s)
        val q = v * (1f - f * s)
        val t = v * (1f - (1f - f) * s)
        val (r, g, b) = when (i) {
            0 -> Triple(v, t, p)
            1 -> Triple(q, v, p)
            2 -> Triple(p, v, t)
            3 -> Triple(p, q, v)
            4 -> Triple(t, p, v)
            else -> Triple(v, p, q)
        }
        return (0xFF shl 24) or
            ((r * 255f + 0.5f).toInt().coerceIn(0, 255) shl 16) or
            ((g * 255f + 0.5f).toInt().coerceIn(0, 255) shl 8) or
            (b * 255f + 0.5f).toInt().coerceIn(0, 255)
    }
}
