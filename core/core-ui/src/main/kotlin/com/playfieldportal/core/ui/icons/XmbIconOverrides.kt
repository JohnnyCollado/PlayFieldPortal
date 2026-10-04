package com.playfieldportal.core.ui.icons

import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
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
    overrideAlpha: Float = 1f,
) {
    // The user's pick, else the applied theme's icon ([XmbIcons]); the built-in tinted vector is last.
    val icon = LocalXmbIcons.current[slotKey]
    if (icon != null) {
        // overrideAlpha dims custom art only (a one-slot, two-state glyph — plan A6); the built-in
        // vector is never dimmed by it.
        CustomIconSurface(icon, contentDescription, if (overrideAlpha < 1f) modifier.alpha(overrideAlpha) else modifier)
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
