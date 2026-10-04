package com.playfieldportal.core.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.playfieldportal.core.ui.icons.CustomIconSurface
import com.playfieldportal.core.ui.icons.LocalXmbIcons

// Theme-Studio MENUS slots. The real render sites draw a PfpCheckMark and a "◀" text glyph, which
// have no vector, so ThemedGlyph (vector-or-override) cannot stand in for them: the site keeps its
// own built-in look and swaps in art only when the user or the applied theme supplies some.

const val MENU_CHECK_KEY = "menu_check"
const val MENU_BACK_KEY = "menu_back"

/**
 * Draws the override for [slotKey] at [size] and returns true; returns false (drawing nothing)
 * when there is none, so the caller renders its built-in glyph instead.
 */
@Composable
fun MenuGlyphOverride(slotKey: String, size: Dp, contentDescription: String? = null): Boolean {
    val icon = LocalXmbIcons.current[slotKey] ?: return false
    CustomIconSurface(icon, contentDescription, Modifier.size(size))
    return true
}
