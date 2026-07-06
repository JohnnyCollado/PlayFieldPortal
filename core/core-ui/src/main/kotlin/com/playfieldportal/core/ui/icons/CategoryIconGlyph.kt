package com.playfieldportal.core.ui.icons

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource

/** Renders the category icon for [iconKey] from its individual drawable (no sprite sheet, no
 *  Canvas). The catalog art is white/monochrome silhouettes, rendered through [PortalIcon] so
 *  the theme's unified icon color applies (white default = visually unchanged). Unknown keys
 *  fall back to the games glyph. Size via [modifier]. */
@Composable
fun CategoryIconGlyph(
    iconKey: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    PortalIcon(
        painter = painterResource(categoryIconFor(iconKey).resId),
        contentDescription = contentDescription,
        modifier = modifier,
    )
}
