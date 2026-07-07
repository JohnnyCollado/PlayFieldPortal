package com.playfieldportal.core.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource

/** Renders the category icon for [iconKey] from its individual drawable (no sprite sheet, no
 *  Canvas). The catalog art is white/monochrome silhouettes, rendered through [PortalIcon] so
 *  the theme's unified icon color applies (white default = visually unchanged). Unknown keys
 *  fall back to the games glyph. Size via [modifier].
 *
 *  Crossbar glyphs are themeable icon slots: when the applied theme carries a custom icon
 *  for this slot, that art renders as-authored instead (console art has no slot). */
@Composable
fun CategoryIconGlyph(
    iconKey: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val override = catbarSlotKeyFor(iconKey)?.let { LocalXmbIconOverrides.current[it] }
    if (override != null) {
        androidx.compose.foundation.Image(
            bitmap = override,
            contentDescription = contentDescription,
            modifier = modifier,
        )
        return
    }
    PortalIcon(
        painter = painterResource(categoryIconFor(iconKey).resId),
        contentDescription = contentDescription,
        modifier = modifier,
    )
}
