package com.playfieldportal.core.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.playfieldportal.core.ui.icons.CustomIcon
import com.playfieldportal.core.ui.icons.CustomIconSurface
import com.playfieldportal.core.ui.icons.LocalCustomIcons
import com.playfieldportal.core.ui.icons.LocalXmbIconOverrides

// Theme-Studio MENUS slots. The real render sites draw a PfpCheckMark and a "◀" text glyph, which
// have no vector, so ThemedGlyph (vector-or-override) cannot stand in for them: the site keeps its
// own built-in look and swaps in art only when the user or the applied theme supplies some.

const val MENU_CHECK_KEY = "menu_check"
const val MENU_BACK_KEY = "menu_back"

/** The override for a menu slot: the user's pick, else the applied theme's icon, else null (built-in look). */
fun menuGlyphOverride(
    slotKey: String,
    userIcons: Map<String, CustomIcon>,
    themeIcons: Map<String, CustomIcon>,
): CustomIcon? = userIcons[slotKey] ?: themeIcons[slotKey]

/**
 * Draws the override for [slotKey] at [size] and returns true; returns false (drawing nothing)
 * when there is none, so the caller renders its built-in glyph instead.
 */
@Composable
fun MenuGlyphOverride(slotKey: String, size: Dp, contentDescription: String? = null): Boolean {
    val icon = menuGlyphOverride(slotKey, LocalCustomIcons.current, LocalXmbIconOverrides.current)
        ?: return false
    CustomIconSurface(icon, contentDescription, Modifier.size(size))
    return true
}
