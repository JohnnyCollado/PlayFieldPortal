package com.playfieldportal.core.domain.model

import kotlin.math.abs

/**
 * A colour preset nudged by word-labelled steps: [tint] toward a neighbouring hue (or cooler and
 * warmer, for a grey) and [depth] lighter or deeper. Both run -[MAX_STEP]..[MAX_STEP], zero being
 * the preset as designed, so each preset offers 25 shades that can be chosen from a label and a
 * full-screen preview. [ColorNaming.tintLabel] and [ColorNaming.depthLabel] name the steps.
 */
data class XmbSchemeVariant(val tint: Int = 0, val depth: Int = 0) {

    val isStandard: Boolean get() = tint == 0 && depth == 0

    /** [delta] more tint steps, stopping at the end of the range. */
    fun withTint(delta: Int): XmbSchemeVariant = of(tint + delta, depth)

    /** [delta] more depth steps, stopping at the end of the range. */
    fun withDepth(delta: Int): XmbSchemeVariant = of(tint, depth + delta)

    /** The stored form, read back by [decode]. */
    fun encode(): String = "$tint,$depth"

    /** [waveArgb] with these steps applied; the standard variant returns it untouched. */
    fun applyTo(waveArgb: Long): Long {
        if (isStandard) return waveArgb
        var (hue, saturation, brightness) = ColorMath.argbToHsv(waveArgb)
        val grey = saturation < ColorNaming.GREY_SATURATION
        if (tint != 0) {
            if (grey) {
                // A grey has no hue worth rotating: slide it along one cool-to-warm axis instead,
                // through neutral, so "Warmer" on a blue-grey first loses the blue.
                val coolness = (if (hue in COOL_HUES) saturation else -saturation) - tint * GREY_TINT_STEP
                hue = if (coolness >= 0f) COOL_HUE else WARM_HUE
                saturation = abs(coolness)
            } else {
                hue = (hue + tint * HUE_STEP + 360f) % 360f
            }
        }
        if (depth != 0) {
            brightness = (brightness - depth * BRIGHTNESS_STEP).coerceIn(MIN_BRIGHTNESS, 1f)
            // Deeper is also richer and lighter also paler, which is what keeps a step visible on
            // a preset already at full brightness. A grey stays a grey.
            if (!grey) saturation = (saturation + depth * SATURATION_STEP).coerceIn(0f, 1f)
        }
        return ColorMath.hsvToArgb(hue, saturation, brightness)
    }

    companion object {
        val STANDARD = XmbSchemeVariant()
        const val MAX_STEP = 2

        private const val HUE_STEP = 12f
        private const val GREY_TINT_STEP = 0.12f
        private const val BRIGHTNESS_STEP = 0.12f
        private const val SATURATION_STEP = 0.08f
        private const val MIN_BRIGHTNESS = 0.12f
        private const val COOL_HUE = 215f
        private const val WARM_HUE = 30f
        private val COOL_HUES = 90f..270f

        /** A variant with both steps held inside the range. */
        fun of(tint: Int, depth: Int): XmbSchemeVariant =
            XmbSchemeVariant(tint.coerceIn(-MAX_STEP, MAX_STEP), depth.coerceIn(-MAX_STEP, MAX_STEP))

        /** Reads [encode]'s form; anything missing or mangled is the standard variant. */
        fun decode(text: String?): XmbSchemeVariant {
            val parts = text?.split(',')?.map { it.trim().toIntOrNull() } ?: return STANDARD
            if (parts.size != 2) return STANDARD
            return of(parts[0] ?: return STANDARD, parts[1] ?: return STANDARD)
        }
    }
}

/** False for the monthly scheme, whose colour is the calendar's to choose. */
val XmbColorScheme.supportsVariants: Boolean get() = this != XmbColorScheme.ORIGINAL

/** [resolve] with [variant]'s tint and depth applied, where the scheme [supportsVariants]. */
fun XmbColorScheme.resolve(month: Int, variant: XmbSchemeVariant): XmbPalette {
    val base = resolve(month)
    if (!supportsVariants || variant.isStandard) return base
    val wave = variant.applyTo(base.waveColor)
    val anchors = lightBackgroundAnchors(wave)
    return base.copy(waveColor = wave, backgroundTop = anchors.first, backgroundBottom = anchors.second)
}
