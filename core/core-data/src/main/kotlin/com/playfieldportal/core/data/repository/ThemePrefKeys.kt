package com.playfieldportal.core.data.repository

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * The prefs a theme reads or writes, declared once. Applying a theme writes them, Display and
 * Themes settings edit them, the XMB renders from them and backup carries them — every module
 * imports these rather than restating the key name with a "must match" comment.
 *
 * The names are a stored contract (prefs on every device, and backups): never rename one.
 */
object ThemePrefKeys {
    // ── Wallpaper ────────────────────────────────────────────────────────────

    /** The still wallpaper's absolute path. */
    val CUSTOM_WALLPAPER = stringPreferencesKey("display_custom_wallpaper")

    /** The motion wallpaper's absolute path (MP4 / WebM / GIF). */
    val MOTION_WALLPAPER = stringPreferencesKey("display_motion_wallpaper")

    /**
     * Compact JSON `{"x":..,"y":..,"w":..,"h":..}` of normalized source-frame fractions, set beside an
     * MP4/WebM motion key only ([PfpThemeStore.encodeMotionCrop]).
     */
    val MOTION_CROP = stringPreferencesKey("display_motion_crop")

    // ── Colours ──────────────────────────────────────────────────────────────

    /** The preset colour scheme's name (Display ▸ Colour Scheme); a theme accent overrides it. */
    val COLOR_SCHEME = stringPreferencesKey("display_color_scheme")

    /** A custom theme's one accent colour, overriding the preset scheme; absent = the preset's. */
    val ACCENT_OVERRIDE = longPreferencesKey("theme_accent_override")

    /** The unified icon tint; absent = white (the icon art's own colour). */
    val ICON_COLOR = longPreferencesKey("theme_icon_color")

    /** Display ▸ Font Colour, which a theme can also carry; absent = the theme's own white. */
    val TEXT_COLOR = longPreferencesKey("display_text_color")

    /** Display ▸ Sub Font Colour; absent = sub text follows [TEXT_COLOR]. */
    val SUB_TEXT_COLOR = longPreferencesKey("display_sub_text_color")

    /** "Use my exact colour": skip the font colour's lightness clamp in Display settings. */
    val TEXT_COLOR_EXACT = booleanPreferencesKey("display_text_color_exact")

    // ── Legibility ───────────────────────────────────────────────────────────

    /** Display ▸ Text Legibility (a TextLegibilityStyle name). */
    val TEXT_LEGIBILITY = stringPreferencesKey("display_text_legibility")

    /** Display ▸ Icon Legibility (an IconLegibilityStyle name). */
    val ICON_LEGIBILITY = stringPreferencesKey("display_icon_legibility")

    /** Draw unfocused icons solid rather than dimmed. */
    val SOLID_UNFOCUSED_ICONS = booleanPreferencesKey("display_solid_unfocused_icons")

    // ── Motion and geometry ──────────────────────────────────────────────────

    /** The wave's motion: ANIMATED, REDUCED, STATIC or REDUCED_STATIC. */
    val WAVE_STYLE = stringPreferencesKey("display_wave_style")

    /** The applied theme's XmbLayoutSpec override as XmbLayoutSpecCodec JSON; absent = the default geometry. */
    val THEME_LAYOUT = stringPreferencesKey("theme_layout_spec")

    // ── The applied theme ────────────────────────────────────────────────────

    /**
     * Present ⇒ the applied theme carries icons in the theme tier ([ThemeTiers]); the value only
     * bumps so observers reload. Removed when a theme or preset without icons applies.
     */
    val THEME_ICONS_STAMP = longPreferencesKey("theme_icons_stamp")

    /**
     * Display name of the theme most recently applied (any source: My Themes, Quick Create, PTF or
     * .pfptheme import). Absence means the stock look. Drives the "Active Theme" row in Settings.
     */
    val APPLIED_THEME_NAME = stringPreferencesKey("theme_applied_name")
}
