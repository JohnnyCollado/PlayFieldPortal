package com.playfieldportal.core.ui.icons

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource

/** Renders the category icon for [iconKey] from its individual drawable (no sprite sheet, no
 *  Canvas). XMB column glyphs and console icons are both full-colour/white bitmaps. Unknown keys
 *  fall back to the games glyph. Size via [modifier]. */
@Composable
fun CategoryIconGlyph(
    iconKey: String,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    Image(
        painter = painterResource(categoryIconFor(iconKey).resId),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}
