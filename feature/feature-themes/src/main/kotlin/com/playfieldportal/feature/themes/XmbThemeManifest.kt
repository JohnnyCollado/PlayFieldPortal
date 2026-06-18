package com.playfieldportal.feature.themes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val THEME_FORMAT_VERSION = 1

/**
 * Parsed representation of theme.json inside a .xmbtheme ZIP package.
 *
 * Colors are stored as hex strings (#RRGGBB or #AARRGGBB) in the JSON so that
 * theme authors can write human-readable values. Call [parseHexColor] to convert
 * to the packed Long format used by [com.playfieldportal.core.data.database.entity.ThemeEntity].
 *
 * **ZIP layout expected by [XmbThemeLoader]:**
 * ```
 * theme.json           ← this manifest (required)
 * background.jpg       ← optional; extracted only when hasBackground = true
 * boot_animation.mp4   ← optional; extracted only when hasBootAnimation = true
 * sounds/
 *   navigate_h.ogg     ← horizontal navigation
 *   navigate_v.ogg     ← vertical navigation
 *   select.ogg
 *   back.ogg
 *   category_change.ogg
 *   boot.ogg
 * ```
 */
@Serializable
data class XmbThemeManifest(
    @SerialName("format_version")    val formatVersion:    Int     = THEME_FORMAT_VERSION,
    val id:                                                 String,
    val name:                                               String,
    val author:                                             String? = null,
    val version:                                            String  = "1.0",
    @SerialName("wave_color")        val waveColor:        String  = "#0055AA",
    @SerialName("wave_opacity")      val waveOpacity:      Float   = 0.7f,
    @SerialName("wave_speed")        val waveSpeed:        Float   = 1.0f,
    @SerialName("wave_amplitude")    val waveAmplitude:    Float   = 1.0f,
    @SerialName("accent_color")      val accentColor:      String  = "#FFFFFF",
    @SerialName("text_color")        val textColor:        String  = "#FFFFFF",
    @SerialName("has_background")    val hasBackground:    Boolean = false,
    @SerialName("has_boot_animation") val hasBootAnimation: Boolean = false,
    @SerialName("has_sound_pack")    val hasSoundPack:     Boolean = false,
    @SerialName("font_key")          val fontKey:          String  = "system_default",
)

/**
 * Converts a CSS-style hex color string to a packed ARGB Long.
 *
 * Accepts:
 * - `#RRGGBB`  → `0xFF______L` (opaque)
 * - `#AARRGGBB` → `0xAA______L`
 * - Variants without the `#` prefix
 * - Lowercase and uppercase hex digits
 *
 * Returns `null` for any other input (wrong length, non-hex characters, etc.).
 */
fun parseHexColor(hex: String): Long? {
    val clean = hex.trimStart('#')
    return when (clean.length) {
        6 -> clean.toLongOrNull(16)?.let { it or 0xFF000000L }
        8 -> clean.toLongOrNull(16)
        else -> null
    }
}
