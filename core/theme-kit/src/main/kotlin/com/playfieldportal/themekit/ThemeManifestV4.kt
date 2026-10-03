package com.playfieldportal.themekit

import kotlinx.serialization.Serializable

/**
 * Format v4 manifest value types and helpers (docs plan 5.1). Everything here is additive and
 * optional: absent means "the theme says nothing", and no reader gates on `schemaVersion`.
 */

/**
 * Legibility settings a theme may carry. Every member is optional; an unrecognized enum string is
 * treated as absent by [sanitized] (read rule 3). Strings rather than enums so a future value
 * written by a newer app survives a decode instead of failing the whole manifest.
 */
@Serializable
data class ThemeLegibility(
    /** One of [TEXT_VALUES]. */
    val text: String? = null,
    /** One of [ICON_VALUES]. */
    val icon: String? = null,
    val solidUnfocusedIcons: Boolean? = null,
) {
    fun sanitized(): ThemeLegibility = copy(
        text = text?.takeIf { it in TEXT_VALUES },
        icon = icon?.takeIf { it in ICON_VALUES },
    )

    companion object {
        val TEXT_VALUES = setOf("auto", "none", "shadow", "outline", "plate")
        val ICON_VALUES = setOf("none", "offset_shadow", "contour_dark", "contour_light", "contour_auto")
    }
}

/** Normalized source-frame crop for MP4/WebM motion wallpaper (ignored for GIF motion). */
@Serializable
data class MotionCrop(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
) {
    /**
     * Read rule 4: non-finite -> null; clamp to [0,1]; `w`,`h` >= [MIN_SIZE]; `x+w <= 1`,
     * `y+h <= 1` by shrinking, never shifting (the origin only moves when it would leave no room
     * for the minimum size).
     */
    fun sanitized(): MotionCrop? {
        if (!x.isFinite() || !y.isFinite() || !w.isFinite() || !h.isFinite()) return null
        val sx = x.coerceIn(0f, 1f - MIN_SIZE)
        val sy = y.coerceIn(0f, 1f - MIN_SIZE)
        return MotionCrop(
            x = sx,
            y = sy,
            w = w.coerceIn(MIN_SIZE, 1f).coerceAtMost(1f - sx),
            h = h.coerceIn(MIN_SIZE, 1f).coerceAtMost(1f - sy),
        )
    }

    companion object {
        const val MIN_SIZE = 0.05f
    }
}

/**
 * Wave style as the v4 manifest carries it: the legacy `waveStyle` field (always written, so
 * older readers keep working) plus the exact `waveStyleV4`.
 */
object WaveStyles {
    private val LEGACY = setOf(
        PfpThemeManifest.WAVE_ANIMATED,
        PfpThemeManifest.WAVE_REDUCED,
        PfpThemeManifest.WAVE_STATIC,
    )
    private val EXACT = LEGACY + PfpThemeManifest.WAVE_REDUCED_STATIC

    fun isExact(value: String): Boolean = value in EXACT

    /** Read rule 2: `waveStyleV4` if recognized, else `waveStyle` if recognized, else animated. */
    fun resolveExact(manifest: PfpThemeManifest): String =
        manifest.waveStyleV4?.takeIf { it in EXACT }
            ?: manifest.waveStyle.takeIf { it in LEGACY }
            ?: PfpThemeManifest.WAVE_ANIMATED

    /** Write rule 1: `(legacy, v4)` for an exact value; `reduced_static` falls back to `static`. */
    fun encode(exact: String): Pair<String, String> {
        val e = exact.takeIf { it in EXACT } ?: PfpThemeManifest.WAVE_ANIMATED
        val legacy = if (e == PfpThemeManifest.WAVE_REDUCED_STATIC) PfpThemeManifest.WAVE_STATIC else e
        return legacy to e
    }
}

/** Longest description kept on read (plan 5.4). */
const val MANIFEST_DESCRIPTION_MAX = 500

/** Applies the read-side sanitizers (rules 3 and 4, description clamp). */
fun PfpThemeManifest.sanitized(): PfpThemeManifest = copy(
    description = description?.take(MANIFEST_DESCRIPTION_MAX),
    legibility = legibility?.sanitized(),
    motionCrop = motionCrop?.sanitized(),
)

/**
 * Write rule 1: stamp the current schema version, and keep the legacy `waveStyle` consistent with
 * an exact `waveStyleV4` when one is present. A manifest with no `waveStyleV4` is left alone.
 */
internal fun PfpThemeManifest.forWrite(): PfpThemeManifest {
    val legacy = waveStyleV4?.takeIf { WaveStyles.isExact(it) }?.let { WaveStyles.encode(it).first }
    return copy(
        schemaVersion = PfpThemeManifest.SCHEMA_VERSION,
        waveStyle = legacy ?: waveStyle,
    )
}
