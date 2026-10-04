package com.playfieldportal.core.ui.icons

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf

/** Which tier an icon came from. */
enum class IconTier { USER, THEME }

/**
 * Every custom icon the XMB can draw, and the one rule between its two tiers: the user's own pick
 * (`custom-icons/`) beats the applied theme's icon (`theme-icons/`), which beats the site's
 * built-in art. Render sites ask [get]; none of them restates the precedence.
 *
 * A user category image (`usercat_<id>`) only ever comes from the user tier — theme bundles never
 * carry those keys, so a stray one in the theme tier must not draw.
 */
@Immutable
class XmbIcons(
    private val user: Map<String, CustomIcon> = emptyMap(),
    private val theme: Map<String, CustomIcon> = emptyMap(),
) {
    /** The icon that draws for [key], or null for the built-in art. */
    operator fun get(key: String): CustomIcon? =
        user[key] ?: theme[key]?.takeUnless { UserCategoryIconKeys.isValidKey(key) }

    /** Which tier supplies [key]'s icon, or null when neither does. */
    fun tierOf(key: String): IconTier? = when {
        key in user -> IconTier.USER
        key in theme && !UserCategoryIconKeys.isValidKey(key) -> IconTier.THEME
        else -> null
    }

    /** The keys the user has set (the icon editor's Reset / Reset All work on these). */
    val userKeys: Set<String> get() = user.keys

    /** The keys the applied theme supplies. */
    val themeKeys: Set<String> get() = theme.keys

    override fun equals(other: Any?): Boolean =
        other is XmbIcons && user == other.user && theme == other.theme

    override fun hashCode(): Int = 31 * user.hashCode() + theme.hashCode()

    companion object {
        val EMPTY = XmbIcons()
    }
}

/**
 * The XMB's custom icons for the subtree, provided by XMBShell beside the palette. Empty when
 * nothing is customized and the applied theme carries no icons, so every site draws its built-in.
 */
val LocalXmbIcons = staticCompositionLocalOf { XmbIcons.EMPTY }
