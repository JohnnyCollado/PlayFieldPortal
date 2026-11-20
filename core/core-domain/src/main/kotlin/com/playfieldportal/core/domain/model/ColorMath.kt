package com.playfieldportal.core.domain.model

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * HSV conversions on opaque 0xFFRRGGBB longs, the launcher's palette model. Pure Kotlin: no
 * Android or AWT colour classes, so the picker, the scheme variants and the colour names are all
 * JVM-testable against the same arithmetic.
 */
object ColorMath {

    /** Opaque 0xFFRRGGBB for an HSV colour; the same rounding as android.graphics.Color.HSVToColor. */
    fun hsvToArgb(hue: Float, saturation: Float, brightness: Float): Long {
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val s = saturation.coerceIn(0f, 1f)
        val v = brightness.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1 - abs(h % 2f - 1))
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = v - c
        fun channel(f: Float) = ((f + m) * 255f).roundToInt().coerceIn(0, 255).toLong()
        return 0xFF000000L or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    /** (hue 0..360, saturation, brightness) of [argb]; alpha is ignored. */
    fun argbToHsv(argb: Long): Triple<Float, Float, Float> {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val delta = max - minOf(r, g, b)
        val hue = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return Triple(hue, if (max == 0f) 0f else delta / max, max)
    }
}
