package com.playfieldportal.studio.io

import com.playfieldportal.themekit.AccentDeriver
import com.playfieldportal.themekit.PfpThemeBundle
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.PfpThemeSource
import com.playfieldportal.themekit.PtfParser
import java.time.LocalDate

/** Result of converting one `.ptf` file. */
sealed interface ConvertOutcome {
    /** [warning] is set when the theme converted but lost something (e.g. its wallpaper). */
    data class Converted(val bundle: PfpThemeBundle, val warning: String? = null) : ConvertOutcome

    /** CXMB flash0 replacement — same magic, different beast; rejected with an explanation. */
    data object Cxmb : ConvertOutcome

    data class Failed(val reason: String) : ConvertOutcome
}

/**
 * The PTF → `.pfptheme` pipeline shared by Open and Batch Convert: parse the official
 * theme, extract the wallpaper, derive the accent from its dominant hue (the PSP stores
 * no usable color of its own), and wrap it all in a bundle with provenance.
 */
object PtfConversion {

    /** Classic Blue — the launcher's default scheme; used when a wallpaper has no dominant hue. */
    const val DEFAULT_ACCENT = 0xFF0055AA.toInt()

    fun convert(
        ptfBytes: ByteArray,
        sourceFileName: String,
        previewPng: ByteArray? = null,
        today: LocalDate = LocalDate.now(),
    ): ConvertOutcome {
        when (PtfParser.detect(ptfBytes)) {
            PtfParser.Kind.CXMB -> return ConvertOutcome.Cxmb
            PtfParser.Kind.NOT_PTF -> return ConvertOutcome.Failed("Not a PSP theme file")
            PtfParser.Kind.OFFICIAL_PTF -> Unit
        }
        val ptf = PtfParser.parse(ptfBytes)
            ?: return ConvertOutcome.Failed("Corrupt or truncated PTF")

        val wallpaperPng = ptf.wallpaper?.let { ImageCodecs.toPngBytes(ImageCodecs.bmpToBufferedImage(it)) }
        val accent = ptf.wallpaper?.let { AccentDeriver.deriveAccent(it) } ?: DEFAULT_ACCENT
        val warning = when (ptf.wallpaperStatus) {
            PtfParser.WallpaperStatus.DECODED -> null
            PtfParser.WallpaperStatus.MISSING -> "This theme contains no wallpaper image."
            PtfParser.WallpaperStatus.UNSUPPORTED_COMPRESSION ->
                "The wallpaper could not be extracted: this theme uses a compression method " +
                    "this importer doesn't recognize. The theme was imported without its wallpaper."
            PtfParser.WallpaperStatus.CORRUPT ->
                "The wallpaper data in this theme is damaged and could not be decoded. " +
                    "The theme was imported without its wallpaper."
        }

        val manifest = PfpThemeManifest(
            name = ptf.name.ifBlank { sourceFileName.substringBeforeLast('.') },
            accentColor = toHexRgb(accent),
            source = PfpThemeSource(
                type = PfpThemeSource.TYPE_PTF_IMPORT,
                file = sourceFileName,
                firmware = ptf.firmware.ifBlank { null },
            ),
            created = today.toString(),
        )
        return ConvertOutcome.Converted(
            PfpThemeBundle(manifest = manifest, wallpaper = wallpaperPng, preview = previewPng),
            warning = warning,
        )
    }

    /** Packed ARGB → the manifest's `#RRGGBB` (alpha is always opaque in the cascade). */
    fun toHexRgb(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    /** `#RRGGBB` (or `#AARRGGBB`) → packed opaque ARGB int, or null when malformed. */
    fun parseHexRgb(hex: String): Int? {
        val digits = hex.removePrefix("#")
        if (digits.length != 6 && digits.length != 8) return null
        val value = digits.toLongOrNull(16) ?: return null
        return (0xFF000000L or (value and 0xFFFFFF)).toInt()
    }
}
