package com.playfieldportal.core.ui.icons

import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
/**
 * Custom icon overrides carried by the applied `.pfptheme` (schema v3, extracted to the
 * theme-icons dir): theme-kit slot key → icon — a [CustomIcon.Still] for png art, a
 * [CustomIcon.Animated] for gif. Empty when the active theme has no custom icons — every
 * consumer falls back to the built-in glyph, so pre-icon themes and presets render exactly
 * as before.
 *
 * Provided by XMBShell alongside LocalPFPColors; loaded by XMBViewModel from the extracted
 * theme-icons dir (see PfpThemeStore.apply). Deliberately a separate composition local from
 * [LocalCustomIcons] (precedence, and the theme-icons wipe on apply, are different concerns),
 * but the same value type — a v3 theme can carry a GIF just as a user pick can.
 */
val LocalXmbIconOverrides = staticCompositionLocalOf<Map<String, CustomIcon>> { emptyMap() }

/**
 * A themeable UI glyph: renders the theme's custom icon for [slotKey] when the applied
 * theme carries one, else the built-in Material [defaultVector] tinted as today.
 *
 * Custom icons draw as-authored (untinted) — like PSP themes, their colors are baked by
 * the theme author; the unified icon tint keeps applying to non-overridden defaults. When
 * an icon-legibility style is configured, a matte drawn from the bitmap's own alpha sits
 * BEHIND the as-authored art (override branch, [OverrideGlyphSurface]), and the default
 * vector branch carries the same matte ([VectorGlyphSurface]) — which is how the setting
 * reaches the main XMB item column's Material-glyph rows.
 */
@Composable
fun ThemedGlyph(
    slotKey: String,
    defaultVector: ImageVector,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    // Two-tier precedence: the user's pick wins and survives theme switches; the applied
    // theme's icon is next; the built-in tinted vector is last.
    val icon = LocalCustomIcons.current[slotKey] ?: LocalXmbIconOverrides.current[slotKey]
    if (icon != null) {
        CustomIconSurface(icon, contentDescription, modifier)
        return
    }
    VectorGlyphSurface(vector = defaultVector, contentDescription = contentDescription, tint = tint, modifier = modifier)
}

/** Category-bar slot key for a category iconKey — null for console art (not themeable). */
fun catbarSlotKeyFor(iconKey: String): String? = CATBAR_SLOT_KEYS[categoryIconFor(iconKey).key]

/**
 * The inverse of [catbarSlotKeyFor]: the catalog iconKey whose art is a slot's built-in
 * default (`catbar_settings` → `ic_settings`), or null when [slotKey] is not a crossbar slot.
 * Lets the icon customizer preview a catbar slot's default without duplicating the mapping —
 * [CATBAR_SLOT_KEYS] stays the one place the pairing is written down.
 */
fun catbarIconKeyFor(slotKey: String): String? = CATBAR_ICON_KEYS[slotKey]

private val CATBAR_SLOT_KEYS: Map<String, String> = mapOf(
    "ic_settings" to "catbar_settings",
    "ic_photos" to "catbar_photos",
    "ic_music" to "catbar_music",
    "ic_videos" to "catbar_video",
    "ic_games" to "catbar_games",
    "ic_network" to "catbar_network",
    "ic_appstore" to "catbar_appstore",
    "ic_social" to "catbar_social",
    "ic_favorites" to "catbar_favorites",
    "ic_achievements" to "catbar_achievements",
)

private val CATBAR_ICON_KEYS: Map<String, String> =
    CATBAR_SLOT_KEYS.entries.associate { (iconKey, slotKey) -> slotKey to iconKey }
