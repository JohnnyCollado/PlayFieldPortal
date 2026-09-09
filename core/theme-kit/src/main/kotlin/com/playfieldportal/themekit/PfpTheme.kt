package com.playfieldportal.themekit

import java.io.File
import java.io.OutputStream
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
    /**
     * Text colour, `#RRGGBB`, or [ICON_COLOR_AUTO] to inherit the theme's own (white on every
     * preset). Additive: SCHEMA_VERSION stays 3 by the same argument the v3 note below makes —
     * a reader that predates this field ignores it and applies the rest.
     */
    val textColor: String = ICON_COLOR_AUTO,
    val waveStyle: String = WAVE_ANIMATED,
    /** Per-theme XMB geometry override; null = the app's default layout. */
    val layout: XmbLayoutSpec? = null,
    val source: PfpThemeSource? = null,
    /** ISO-8601 date the bundle was created, e.g. "2026-07-06". */
    val created: String? = null,
) {
    companion object {
        const val MANIFEST_TYPE = "pfptheme"
        // v2: optional icons/<key>.png entries (custom icon slots). v3 (additive): icons
        // widen to <key>.{png,gif}, console art arrives as sysicons/<platformId>.{png,gif},
        // and motion wallpaper travels as motion.<mp4|webm|gif>. Readers never gate on the
        // version — older apps simply ignore the entries they don't know and apply the
        // wallpaper + colors subset, so a v3 bundle still opens everywhere older builds do.
        const val SCHEMA_VERSION = 3
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
 * The motion wallpaper entry — described, not materialised.
 *
 * Every other part of a bundle is a [ThemeImage]: a wallpaper is a megabyte or two, an icon a
 * handful of kilobytes, and holding those as `ByteArray` costs nothing worth avoiding. A motion
 * wallpaper is a video, routinely tens of megabytes, and the launcher's heap is capped at the
 * device's `dalvik.vm.heapgrowthlimit` (256 MB on a Thor) with a live Compose UI already in it.
 * Holding one as a `ByteArray` cost roughly three copies — the source bundle, the
 * `ByteArrayOutputStream` that doubled past it while inflating, and the array copied out of it —
 * which is what made a 52 MB theme import, list and apply as `OutOfMemoryError`.
 *
 * So this type carries no bytes. It knows its extension and how to *stream* itself somewhere,
 * once, on demand. Both directions use it: [PfpThemeCodec.write] streams it into the zip, and a
 * bundle read back from a file streams it out of that file.
 *
 * The content is therefore not held, which has one consequence worth stating plainly: two
 * `ThemeMotion` values compare by extension, never by content. Comparing two videos byte-for-byte
 * would mean reading both into memory, which is the thing this type exists to prevent.
 */
class ThemeMotion internal constructor(
    /** Extension without the dot — "mp4", "webm", "gif". Determines the zip entry name. */
    val extension: String,
    private val copy: (OutputStream) -> Long,
) {
    /** Streams the content to [out] and returns the byte count. Reads the source each time. */
    fun copyTo(out: OutputStream): Long = copy(out)

    override fun equals(other: Any?): Boolean = other is ThemeMotion && extension == other.extension

    override fun hashCode(): Int = extension.hashCode()

    override fun toString(): String = "ThemeMotion($extension)"

    companion object {
        /**
         * Motion backed by a file on disk — the export side, where the launcher's current motion
         * wallpaper already lives in `filesDir/wallpaper`.
         */
        fun ofFile(file: File, extension: String = file.extension.lowercase()): ThemeMotion =
            ThemeMotion(extension) { out -> file.inputStream().use { it.copyTo(out) } }

        /** Motion backed by bytes already in hand. For tests and small synthetic bundles. */
        fun ofBytes(bytes: ByteArray, extension: String): ThemeMotion =
            ThemeMotion(extension) { out -> out.write(bytes); bytes.size.toLong() }
    }
}

/** An encoded image entry in a bundle: opaque bytes plus the file extension it travels as. */
data class ThemeImage(
    val bytes: ByteArray,
    /** Extension without the dot — "png", "gif", "mp4"... Determines the zip entry name. */
    val extension: String,
) {
    override fun equals(other: Any?): Boolean =
        other is ThemeImage && extension == other.extension && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * bytes.contentHashCode() + extension.hashCode()
}

/**
 * A fully-loaded theme bundle: manifest plus the encoded image entries. Image bytes are
 * whatever the writer encoded (PNG by convention; GIF for animated icons) — this module does
 * not do image codecs.
 */
data class PfpThemeBundle(
    val manifest: PfpThemeManifest,
    /** Encoded wallpaper image, or null for wave-only themes. */
    val wallpaper: ByteArray?,
    /** Encoded preview render; always written by the app's preview gate, but optional on read. */
    val preview: ByteArray?,
    /**
     * Custom icon overrides: [IconSlots] key → encoded image. Slots not present render the
     * built-in glyph. Stored as `icons/<key>.<ext>` entries (v2 was png-only; v3 widens to
     * gif for animated icons).
     */
    val icons: Map<String, ThemeImage> = emptyMap(),
    /**
     * Console art overrides (v3): platform id → encoded image, stored as
     * `sysicons/<platformId>.<ext>` entries. Gated by CustomizableIcons' console keys —
     * NOT part of IconSlots.ALL, so the desktop Studio's slot list is unaffected.
     */
    val sysicons: Map<String, ThemeImage> = emptyMap(),
    /**
     * Motion wallpaper (v3), or null for none.
     *
     * A [ThemeMotion] rather than a [ThemeImage] on purpose — see that type for why. Note that a
     * bundle read from a plain [java.io.InputStream] always has this null: a stream is one-pass,
     * so there is nothing to stream the entry back out of later.
     */
    val motion: ThemeMotion? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is PfpThemeBundle &&
            manifest == other.manifest &&
            wallpaper.contentEquals(other.wallpaper) &&
            preview.contentEquals(other.preview) &&
            icons.keys == other.icons.keys &&
            icons.all { (key, image) -> image == other.icons[key] } &&
            sysicons.keys == other.sysicons.keys &&
            sysicons.all { (key, image) -> image == other.sysicons[key] } &&
            motion == other.motion

    override fun hashCode(): Int {
        var h = 31 * (31 * manifest.hashCode() + wallpaper.contentHashCode()) + preview.contentHashCode()
        for ((key, image) in icons) h = 31 * h + (key.hashCode() xor image.hashCode())
        for ((key, image) in sysicons) h = 31 * h + (key.hashCode() xor image.hashCode())
        motion?.let { h = 31 * h + it.hashCode() }
        return h
    }
}
