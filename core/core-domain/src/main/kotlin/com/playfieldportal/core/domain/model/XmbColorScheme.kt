package com.playfieldportal.core.domain.model

/**
 * Selectable XMB color schemes, in the spirit of the classic PSP.
 *
 * [ORIGINAL] reproduces the PSP "Original" theme whose background color changes with the
 * calendar month. All other entries are fixed presets. Resolve a scheme to a concrete
 * [XmbPalette] with [resolve], passing the current month (1-12) — only [ORIGINAL] uses it.
 */
enum class XmbColorScheme {
    ORIGINAL,
    CLASSIC_BLUE,
    SUNSET_ORANGE,
    FRESH_GREEN,
    ROYAL_PURPLE,
    CRIMSON_RED,
    SILVER_MONO,
    SAKURA_PINK,
    GOLDEN_AMBER,
    AQUA_TEAL,
    MIDNIGHT_NAVY,
    CHARCOAL,
}

/** A fully-resolved color palette for the XMB. Colors are ARGB longs (0xAARRGGBB). */
data class XmbPalette(
    val waveColor: Long,
    val accentColor: Long,
    val textColor: Long,
    val backgroundTop: Long,
    val backgroundBottom: Long,
)

fun XmbColorScheme.displayLabel(): String = when (this) {
    XmbColorScheme.ORIGINAL      -> "Original (Monthly)"
    XmbColorScheme.CLASSIC_BLUE  -> "Classic Blue"
    XmbColorScheme.SUNSET_ORANGE -> "Sunset Orange"
    XmbColorScheme.FRESH_GREEN   -> "Fresh Green"
    XmbColorScheme.ROYAL_PURPLE  -> "Royal Purple"
    XmbColorScheme.CRIMSON_RED   -> "Crimson Red"
    XmbColorScheme.SILVER_MONO   -> "Silver"
    XmbColorScheme.SAKURA_PINK   -> "Sakura Pink"
    XmbColorScheme.GOLDEN_AMBER  -> "Golden Amber"
    XmbColorScheme.AQUA_TEAL     -> "Aqua Teal"
    XmbColorScheme.MIDNIGHT_NAVY -> "Midnight Navy"
    XmbColorScheme.CHARCOAL      -> "Charcoal"
}

/**
 * Resolve this scheme to a concrete palette. [month] is 1-12 and only affects [ORIGINAL];
 * out-of-range values are clamped.
 */
fun XmbColorScheme.resolve(month: Int): XmbPalette {
    val wave = when (this) {
        XmbColorScheme.ORIGINAL      -> ORIGINAL_MONTH_WAVE[month.coerceIn(1, 12) - 1]
        XmbColorScheme.CLASSIC_BLUE  -> 0xFF0055AAL
        XmbColorScheme.SUNSET_ORANGE -> 0xFFFF8A3DL
        XmbColorScheme.FRESH_GREEN   -> 0xFF36C26BL
        XmbColorScheme.ROYAL_PURPLE  -> 0xFF7A4DD6L
        XmbColorScheme.CRIMSON_RED   -> 0xFFE03B4FL
        XmbColorScheme.SILVER_MONO   -> 0xFFB8C4D0L
        XmbColorScheme.SAKURA_PINK   -> 0xFFE87FB0L
        XmbColorScheme.GOLDEN_AMBER  -> 0xFFE0A32EL
        XmbColorScheme.AQUA_TEAL     -> 0xFF2EC4B6L
        XmbColorScheme.MIDNIGHT_NAVY -> 0xFF23477EL
        XmbColorScheme.CHARCOAL      -> 0xFF4A505AL
    }
    return XmbPalette(
        waveColor        = wave,
        accentColor      = 0xFFFFFFFFL,
        textColor        = 0xFFFFFFFFL,
        backgroundTop    = lightBackgroundAnchors(wave).first,
        backgroundBottom = lightBackgroundAnchors(wave).second,
    )
}

/**
 * Vertical gradient anchors derived from the wave hue, in the classic PSP "Original" style: a DEEP,
 * saturated shade of the hue at the TOP (`first`) easing to a BRIGHTER shade near the wave (`second`).
 * Dark → bright, top → bottom — but both keep the hue's saturation (the real XMB gradient stays a
 * rich colour, it does not fade to navy/white). Used by [resolve] and re-applied when a category
 * tints the wave so the two always match.
 */
fun lightBackgroundAnchors(waveArgb: Long): Pair<Long, Long> =
    darken(waveArgb, 0.62f) to lighten(waveArgb, 0.28f)

/** Blend the RGB channels of an opaque ARGB color toward white by [t] (0 = unchanged, 1 = white). */
private fun lighten(argb: Long, t: Float): Long {
    val r = (argb shr 16) and 0xFFL
    val g = (argb shr 8) and 0xFFL
    val b = argb and 0xFFL
    val nr = (r + (255 - r) * t).toLong().coerceIn(0L, 255L)
    val ng = (g + (255 - g) * t).toLong().coerceIn(0L, 255L)
    val nb = (b + (255 - b) * t).toLong().coerceIn(0L, 255L)
    return (0xFFL shl 24) or (nr shl 16) or (ng shl 8) or nb
}

// PSP "Original" theme — approximate wave color for each month, Jan..Dec.
private val ORIGINAL_MONTH_WAVE = longArrayOf(
    0xFF1FA89CL, // Jan — teal
    0xFFE56BA0L, // Feb — pink
    0xFF6FBF3BL, // Mar — green
    0xFFE99BC4L, // Apr — sakura
    0xFF34B3A0L, // May — aqua-green
    0xFF3A7BD5L, // Jun — blue
    0xFF35B6D6L, // Jul — light aqua
    0xFF2E54A8L, // Aug — deep blue
    0xFFE08A2EL, // Sep — amber
    0xFF8A5AC2L, // Oct — purple
    0xFFB5642EL, // Nov — autumn brown
    0xFFD23B4EL, // Dec — red
)

/** Multiply the RGB channels of an opaque ARGB color by [factor] (0 = black, 1 = unchanged). */
private fun darken(argb: Long, factor: Float): Long {
    val r = (((argb shr 16) and 0xFFL) * factor).toLong().coerceIn(0L, 255L)
    val g = (((argb shr 8) and 0xFFL) * factor).toLong().coerceIn(0L, 255L)
    val b = ((argb and 0xFFL) * factor).toLong().coerceIn(0L, 255L)
    return (0xFFL shl 24) or (r shl 16) or (g shl 8) or b
}
