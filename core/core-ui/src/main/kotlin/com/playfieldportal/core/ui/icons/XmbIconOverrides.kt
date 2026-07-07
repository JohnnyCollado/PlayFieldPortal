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
 * Custom icon overrides carried by the applied `.pfptheme` (schema v2): theme-kit IconSlots
 * key → decoded bitmap. Empty when the active theme has no custom icons — every consumer
 * falls back to the built-in glyph, so pre-icon themes and presets render exactly as before.
 *
 * Provided by XMBShell alongside LocalPFPColors; loaded by XMBViewModel from the extracted
 * theme-icons dir (see PfpThemeStore.apply).
 */
val LocalXmbIconOverrides = staticCompositionLocalOf<Map<String, ImageBitmap>> { emptyMap() }

/**
 * A themeable UI glyph: renders the theme's custom icon for [slotKey] when the applied
 * theme carries one, else the built-in Material [defaultVector] tinted as today.
 *
 * Custom icons draw as-authored (untinted) — like PSP themes, their colors are baked by
 * the theme author; the unified icon tint keeps applying to non-overridden defaults.
 */
@Composable
fun ThemedGlyph(
    slotKey: String,
    defaultVector: ImageVector,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    val override = LocalXmbIconOverrides.current[slotKey]
    if (override != null) {
        Image(bitmap = override, contentDescription = contentDescription, modifier = modifier)
    } else {
        Icon(defaultVector, contentDescription = contentDescription, tint = tint, modifier = modifier)
    }
}

/** Category-bar slot key for a category iconKey — null for console art (not themeable). */
fun catbarSlotKeyFor(iconKey: String): String? = CATBAR_SLOT_KEYS[categoryIconFor(iconKey).key]

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
)
