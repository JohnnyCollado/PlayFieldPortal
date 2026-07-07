package com.playfieldportal.themekit

import kotlinx.serialization.json.Json

/**
 * Serialization + sanitization for per-theme [XmbLayoutSpec] overrides.
 *
 * The launcher persists an applied theme's layout as a single prefs string; the Studio and
 * the codec read specs from untrusted manifests. Every read path funnels through
 * [sanitize] so a hostile or hand-mangled theme can never wedge the XMB offscreen or blow
 * text/icons up to absurd sizes — values clamp into workable ranges, NaN/Infinity fall
 * back to the field's default.
 */
object XmbLayoutSpecCodec {

    const val BAR_TOP_MIN = 0.05f
    const val BAR_TOP_MAX = 0.45f

    // Compact (no prettyPrint — this is a prefs value); lenient on unknown keys so newer
    // specs still decode on older builds.
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(spec: XmbLayoutSpec): String =
        json.encodeToString(XmbLayoutSpec.serializer(), sanitize(spec))

    /** Lenient decode: null on malformed input, sanitized otherwise. */
    fun decode(encoded: String?): XmbLayoutSpec? {
        if (encoded.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(XmbLayoutSpec.serializer(), encoded) }
            .getOrNull()
            ?.let(::sanitize)
    }

    /** Clamps every field into a safe, renderable range. */
    fun sanitize(spec: XmbLayoutSpec): XmbLayoutSpec {
        val d = XmbLayoutSpec.DEFAULT
        return XmbLayoutSpec(
            barTopFraction = spec.barTopFraction.safe(d.barTopFraction, BAR_TOP_MIN, BAR_TOP_MAX),
            contentTopPaddingDp = spec.contentTopPaddingDp.safe(d.contentTopPaddingDp, 0f, 120f),
            categoryIconSelectedDp = spec.categoryIconSelectedDp.safe(d.categoryIconSelectedDp, 16f, 160f),
            categoryIconDp = spec.categoryIconDp.safe(d.categoryIconDp, 16f, 160f),
            itemIconDp = spec.itemIconDp.safe(d.itemIconDp, 16f, 160f),
            itemIconSlotDp = spec.itemIconSlotDp.safe(d.itemIconSlotDp, 16f, 160f),
            itemTextSelectedSp = spec.itemTextSelectedSp.safe(d.itemTextSelectedSp, 8f, 40f),
            itemTextSp = spec.itemTextSp.safe(d.itemTextSp, 8f, 40f),
            itemTextStartGapDp = spec.itemTextStartGapDp.safe(d.itemTextStartGapDp, 0f, 60f),
            leftAnchorExtraDp = spec.leftAnchorExtraDp.safe(d.leftAnchorExtraDp, -60f, 120f),
            previousItemRiseRows = spec.previousItemRiseRows.safe(d.previousItemRiseRows, 0f, 2f),
        )
    }

    private fun Float.safe(default: Float, min: Float, max: Float): Float =
        (if (isFinite()) this else default).coerceIn(min, max)
}
