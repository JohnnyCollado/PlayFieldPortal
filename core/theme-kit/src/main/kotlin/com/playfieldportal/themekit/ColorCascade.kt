package com.playfieldportal.themekit

/**
 * The one-color cascade's gradient math, shared verbatim by the launcher and the desktop
 * Theme Studio so the preview a theme is authored against can never drift from what the
 * XMB renders. Colors are packed opaque ARGB in a Long (0xFFRRGGBB), matching the
 * launcher's palette model.
 */
object ColorCascade {

    /**
     * Vertical gradient anchors derived from the wave hue, in the classic PSP "Original" style:
     * a DEEP, saturated shade of the hue at the TOP (`first`) easing to a BRIGHTER shade near
     * the wave (`second`). Dark → bright, top → bottom — but both keep the hue's saturation
     * (the real XMB gradient stays a rich colour, it does not fade to navy/white).
     */
    fun lightBackgroundAnchors(waveArgb: Long): Pair<Long, Long> =
        darken(waveArgb, 0.62f) to lighten(waveArgb, 0.28f)

    /** Blend the RGB channels of an opaque ARGB color toward white by [t] (0 = unchanged, 1 = white). */
    fun lighten(argb: Long, t: Float): Long {
        val r = (argb shr 16) and 0xFFL
        val g = (argb shr 8) and 0xFFL
        val b = argb and 0xFFL
        val nr = (r + (255 - r) * t).toLong().coerceIn(0L, 255L)
        val ng = (g + (255 - g) * t).toLong().coerceIn(0L, 255L)
        val nb = (b + (255 - b) * t).toLong().coerceIn(0L, 255L)
        return (0xFFL shl 24) or (nr shl 16) or (ng shl 8) or nb
    }

    /** Multiply the RGB channels of an opaque ARGB color by [factor] (0 = black, 1 = unchanged). */
    fun darken(argb: Long, factor: Float): Long {
        val r = (((argb shr 16) and 0xFFL) * factor).toLong().coerceIn(0L, 255L)
        val g = (((argb shr 8) and 0xFFL) * factor).toLong().coerceIn(0L, 255L)
        val b = ((argb and 0xFFL) * factor).toLong().coerceIn(0L, 255L)
        return (0xFFL shl 24) or (r shl 16) or (g shl 8) or b
    }
}
