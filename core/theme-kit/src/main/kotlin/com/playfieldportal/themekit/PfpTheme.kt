package com.playfieldportal.themekit

import kotlinx.serialization.Serializable

/**
 * `.pfptheme` manifest — the JSON descriptor inside the theme bundle
 * (see docs/xmb-theme-creator-plan.md, "`.pfptheme` file format").
 *
 * Spiritually a modern descendant of Sony's `PSPTheme_default.txt` project file: a small
 * manifest naming the theme's parts, with the parts carried alongside. Because the entire
 * palette derives from [accentColor] (one-color cascade), the manifest stays tiny.
 */
@Serializable
data class PfpThemeManifest(
    val manifest: String = MANIFEST_TYPE,
    val schemaVersion: Int = SCHEMA_VERSION,
    val name: String,
    /** The one color everything derives from, as `#RRGGBB`. */
    val accentColor: String,
    /** `#RRGGBB`, or [ICON_COLOR_AUTO] to derive from the accent at apply time. */
    val iconColor: String = ICON_COLOR_AUTO,
    val waveStyle: String = WAVE_ANIMATED,
    /** Per-theme XMB geometry override; null = the app's default layout. */
    val layout: XmbLayoutSpec? = null,
    val source: PfpThemeSource? = null,
    /** ISO-8601 date the bundle was created, e.g. "2026-07-06". */
    val created: String? = null,
) {
    companion object {
        const val MANIFEST_TYPE = "pfptheme"
        const val SCHEMA_VERSION = 1
        const val ICON_COLOR_AUTO = "auto"
        const val WAVE_ANIMATED = "animated"
        const val WAVE_STATIC = "static"
        const val WAVE_REDUCED = "reduced"
    }
}

/** Provenance of an imported theme (e.g. a converted PSP `.ptf`). */
@Serializable
data class PfpThemeSource(
    val type: String,
    val file: String? = null,
    val firmware: String? = null,
) {
    companion object {
        const val TYPE_PTF_IMPORT = "ptf-import"
        const val TYPE_USER_CREATED = "user-created"
    }
}

/**
 * A fully-loaded theme bundle: manifest plus the encoded image entries. Image bytes are
 * whatever the writer encoded (PNG by convention) — this module does not do image codecs.
 */
data class PfpThemeBundle(
    val manifest: PfpThemeManifest,
    /** Encoded wallpaper image, or null for wave-only themes. */
    val wallpaper: ByteArray?,
    /** Encoded preview render; always written by the app's preview gate, but optional on read. */
    val preview: ByteArray?,
) {
    override fun equals(other: Any?): Boolean =
        other is PfpThemeBundle &&
            manifest == other.manifest &&
            wallpaper.contentEquals(other.wallpaper) &&
            preview.contentEquals(other.preview)

    override fun hashCode(): Int =
        31 * (31 * manifest.hashCode() + wallpaper.contentHashCode()) + preview.contentHashCode()
}
