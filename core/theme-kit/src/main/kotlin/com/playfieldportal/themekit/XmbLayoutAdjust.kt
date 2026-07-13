package com.playfieldportal.themekit

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * User-tunable XMB placement, layered on top of the resolved [XmbLayoutSpec]. Three axes the live
 * "Adjust XMB Layout" editor writes:
 *
 *  - [scale]: multiplier on the automatic canvas scale (density). 1 = untouched.
 *  - [barLeftFraction]: horizontal shift of the whole cross, as a fraction of screen width.
 *    Negative moves left, positive right. 0 = the spec's default anchor.
 *  - [barTopFraction]: absolute vertical position of the crossbar (fraction of height), same
 *    meaning as [XmbLayoutSpec.barTopFraction]; overrides it when the user has tuned this bucket.
 *
 * Stored per form-factor bucket (see [XmbFormFactor]) so a handheld and a foldable/tablet each
 * keep their own tuning — one screen's offsets never distort another's.
 */
@Serializable
data class XmbLayoutAdjust(
    val scale: Float = 1f,
    val barLeftFraction: Float = 0f,
    val barTopFraction: Float = XmbLayoutSpec.DEFAULT.barTopFraction,
) {
    companion object {
        val DEFAULT = XmbLayoutAdjust()

        const val SCALE_MIN = 0.6f
        const val SCALE_MAX = 1.8f
        const val LEFT_MIN = -0.25f
        const val LEFT_MAX = 0.35f
        // Vertical reuses the layout spec's safe band so the bar can never be driven off-screen.
        const val TOP_MIN = XmbLayoutSpecCodec.BAR_TOP_MIN
        const val TOP_MAX = XmbLayoutSpecCodec.BAR_TOP_MAX
    }
}

/**
 * Coarse screen buckets the layout adjustment is keyed by. Derived from the window's
 * smallest-width dp so it's stable across rotation and tracks fold/unfold on a foldable.
 */
enum class XmbFormFactor(val key: String) {
    COMPACT("compact"),   // phones / handhelds (the Thor)
    MEDIUM("medium"),     // large phones unfolded outer, small tablets
    EXPANDED("expanded"); // foldable inner display, tablets (the Z Fold)

    companion object {
        fun forSmallestWidthDp(swDp: Int): XmbFormFactor = when {
            swDp < 600 -> COMPACT
            swDp < 840 -> MEDIUM
            else -> EXPANDED
        }
    }
}

/** Serialization + clamping for the per-bucket [XmbLayoutAdjust] map (one prefs string). */
object XmbLayoutAdjustCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val mapSerializer = MapSerializer(String.serializer(), XmbLayoutAdjust.serializer())

    fun encode(map: Map<String, XmbLayoutAdjust>): String =
        json.encodeToString(mapSerializer, map.mapValues { sanitize(it.value) })

    fun decode(encoded: String?): Map<String, XmbLayoutAdjust> {
        if (encoded.isNullOrBlank()) return emptyMap()
        return runCatching { json.decodeFromString(mapSerializer, encoded) }
            .getOrNull()
            ?.mapValues { sanitize(it.value) }
            ?: emptyMap()
    }

    fun sanitize(a: XmbLayoutAdjust): XmbLayoutAdjust = XmbLayoutAdjust(
        scale = a.scale.safe(XmbLayoutAdjust.DEFAULT.scale).coerceIn(XmbLayoutAdjust.SCALE_MIN, XmbLayoutAdjust.SCALE_MAX),
        barLeftFraction = a.barLeftFraction.safe(0f).coerceIn(XmbLayoutAdjust.LEFT_MIN, XmbLayoutAdjust.LEFT_MAX),
        barTopFraction = a.barTopFraction.safe(XmbLayoutAdjust.DEFAULT.barTopFraction)
            .coerceIn(XmbLayoutAdjust.TOP_MIN, XmbLayoutAdjust.TOP_MAX),
    )

    private fun Float.safe(fallback: Float): Float = if (isNaN() || isInfinite()) fallback else this
}
