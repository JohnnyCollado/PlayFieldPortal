package com.playfieldportal.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Semantic color roles for the PSP-era storefront presentation layer (App Drawer).
 *
 * Every color is derived from the user's current XMB accent and background gradient, so the
 * storefront chrome re-interprets the layout with each theme change — exactly the behavior
 * [com.playfieldportal.core.domain.model.XmbColorScheme.resolve] demands.
 */
@Immutable
data class StorefrontColors(
    /** Deep header / chrome top gradient stop. */
    val chromeTop: Color,
    /** Header / chrome bottom gradient stop. */
    val chromeBottom: Color,
    /** Thin cyan/light-blue separator line under the header. */
    val chromeDivider: Color,
    /** Fill behind the selected category in the rail. */
    val categorySelected: Color,
    /** Right-edge glow of the selected category. */
    val categorySelectedEdge: Color,
    /** Fill behind inactive categories. */
    val categoryInactive: Color,
    /** Tile background in normal (unselected) state. */
    val tileNormal: Color,
    /** Tile background in selected / focused state. */
    val tileSelected: Color,
    /** Bright outer border of the focused tile. */
    val tileSelectedEdge: Color,
    /** Inner glow / secondary border of the focused tile. */
    val tileSelectedInner: Color,
    /** Footer / controller command bar background. */
    val footerBackground: Color,
    /** Footer divider line above the command bar. */
    val footerDivider: Color,
    /** Primary text colour (labels, breadcrumb, tile names). */
    val textPrimary: Color,
    /** Secondary / muted text (counts, sublabels). */
    val textSecondary: Color,
    /** Content area background — semi-transparent so the wave shows through. */
    val contentBackground: Color,
    /** Panel background for the category rail. */
    val railBackground: Color,
    /** Search input field background. */
    val searchField: Color,
    /** Search input border. */
    val searchBorder: Color,
    /** Overlay dim behind mini menu / dialogs. */
    val overlayDim: Color,
    /** Mini menu / dialog panel background. */
    val menuPanel: Color,
    /** Selected row inside the mini menu. */
    val menuRowSelected: Color,
    /** Destructive action color (uninstall). */
    val destructive: Color,
)

private val DefaultStorefrontColors = StorefrontColors(
    chromeTop        = Color(0xFF0743A2),
    chromeBottom     = Color(0xFF128BC9),
    chromeDivider    = Color(0xFF7EE8FF),
    categorySelected = Color(0xFF006BC4),
    categorySelectedEdge = Color(0xFF7EE8FF),
    categoryInactive = Color(0xFF0874BE),
    tileNormal       = Color(0xFF083880),
    tileSelected     = Color(0xFF0B4FAA),
    tileSelectedEdge = Color(0xFF7EE8FF),
    tileSelectedInner = Color(0xFF4CCFFF),
    footerBackground = Color(0xFF003C8F),
    footerDivider    = Color(0xFF68C9EB),
    textPrimary      = Color.White,
    textSecondary    = Color(0xFFD6EDF7),
    contentBackground = Color(0x00000000),
    railBackground   = Color(0x30004590),
    searchField      = Color(0xFF0A2E5A),
    searchBorder     = Color(0xFF68C9EB),
    overlayDim       = Color(0x99000000),
    menuPanel        = Color(0xF00A1E3D),
    menuRowSelected  = Color(0x347EE8FF),
    destructive      = Color(0xFFFF6B6B),
)

val LocalStorefrontColors = staticCompositionLocalOf { DefaultStorefrontColors }

/**
 * Derive a [StorefrontColors] from the live [LocalPFPColors].
 *
 * The accent and background gradient come from the user's active XMB theme; the storefront
 * palette is constructed so that blues and cyans dominate while still respecting the accent
 * tint in selection states and chrome.
 */
@Composable
fun deriveStorefrontColors(): StorefrontColors {
    val pfp = LocalPFPColors.current
    val accent = pfp.accentColor
    val bgTop = pfp.backgroundTop
    val bgBottom = pfp.backgroundBottom

    // ── Header chrome ────────────────────────────────────────────────────
    // Use the XMB background gradient as the header chrome base.
    val chromeTop = bgTop.copy(alpha = 0.96f)
    val chromeBottom = lerp(bgTop, bgBottom, 0.55f).copy(alpha = 0.96f)
    // Cyan divider line — tinted slightly toward the accent.
    val chromeDivider = lerp(Color(0xFF7EE8FF), accent, 0.15f)

    // ── Category rail ────────────────────────────────────────────────────
    val categorySelected = lerp(bgBottom, accent, 0.35f).copy(alpha = 0.92f)
    val categorySelectedEdge = lerp(Color(0xFF7EE8FF), accent, 0.2f)
    val categoryInactive = bgTop.copy(alpha = 0.50f)

    // ── Tiles ────────────────────────────────────────────────────────────
    val tileNormal = lerp(Color(0xFF083880), bgTop, 0.3f).copy(alpha = 0.85f)
    val tileSelected = lerp(Color(0xFF0B4FAA), accent, 0.25f).copy(alpha = 0.95f)
    val tileSelectedEdge = lerp(Color(0xFF7EE8FF), accent, 0.12f)
    val tileSelectedInner = lerp(Color(0xFF4CCFFF), accent, 0.18f)

    // ── Footer ───────────────────────────────────────────────────────────
    val footerBackground = lerp(Color(0xFF003C8F), bgTop, 0.5f).copy(alpha = 0.98f)
    val footerDivider = lerp(Color(0xFF68C9EB), accent, 0.1f)

    // ── Text ─────────────────────────────────────────────────────────────
    val textPrimary = Color.White
    val textSecondary = lerp(Color(0xFFD6EDF7), accent, 0.1f)

    // ── Content / rail ───────────────────────────────────────────────────
    val contentBackground = Color(0x00000000)  // transparent — wave shows through
    val railBackground = bgTop.copy(alpha = 0.35f)

    // ── Search ───────────────────────────────────────────────────────────
    val searchField = lerp(Color(0xFF0A2E5A), bgTop, 0.4f)
    val searchBorder = footerDivider

    // ── Mini menu ────────────────────────────────────────────────────────
    val overlayDim = Color(0x99000000)
    val menuPanel = lerp(Color(0xF00A1E3D), bgTop, 0.35f)
    val menuRowSelected = tileSelectedEdge.copy(alpha = 0.20f)

    return StorefrontColors(
        chromeTop           = chromeTop,
        chromeBottom        = chromeBottom,
        chromeDivider       = chromeDivider,
        categorySelected    = categorySelected,
        categorySelectedEdge = categorySelectedEdge,
        categoryInactive    = categoryInactive,
        tileNormal          = tileNormal,
        tileSelected        = tileSelected,
        tileSelectedEdge    = tileSelectedEdge,
        tileSelectedInner   = tileSelectedInner,
        footerBackground    = footerBackground,
        footerDivider       = footerDivider,
        textPrimary         = textPrimary,
        textSecondary       = textSecondary,
        contentBackground   = contentBackground,
        railBackground      = railBackground,
        searchField         = searchField,
        searchBorder        = searchBorder,
        overlayDim          = overlayDim,
        menuPanel           = menuPanel,
        menuRowSelected     = menuRowSelected,
        destructive         = Color(0xFFFF6B6B),
    )
}
