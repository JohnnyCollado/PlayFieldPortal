package com.playfieldportal.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Semantic color roles for the PSP-era presentation layers (the redesigned grid App Drawer and
 * the preserved storefront layout that will back RSS Channels).
 *
 * Every color is derived from the user's current XMB theme so the chrome re-interprets the layout
 * with each theme change — exactly the behavior
 * [com.playfieldportal.core.domain.model.XmbColorScheme.resolve] demands. The real hue source is
 * the wave color (see [deriveStorefrontColors]): preset schemes resolve their accent to white, so
 * the accent only participates when a custom theme supplies a genuine hue.
 */
@Immutable
data class StorefrontColors(
    /** Deep upper (header) region of the background gradient. */
    val backgroundDeep: Color,
    /** Rich midtone (grid) region of the background gradient. */
    val backgroundMid: Color,
    /** Very low-alpha accent wash placed behind the selected tile's artwork. */
    val selectionGlow: Color,
    /** Deep header / chrome top gradient stop (preserved storefront). */
    val chromeTop: Color,
    /** Header / chrome bottom gradient stop (preserved storefront). */
    val chromeBottom: Color,
    /** Thin accent separator line under the header / bright accent edge. */
    val chromeDivider: Color,
    /** Fill behind the selected category in the rail (preserved storefront). */
    val categorySelected: Color,
    /** Right-edge glow of the selected category. */
    val categorySelectedEdge: Color,
    /** Fill behind inactive categories (preserved storefront). */
    val categoryInactive: Color,
    /** Tile background in normal (unselected) state (preserved storefront). */
    val tileNormal: Color,
    /** Tile background in selected / focused state (preserved storefront). */
    val tileSelected: Color,
    /** Bright outer border of the focused tile / selection edge. */
    val tileSelectedEdge: Color,
    /** Inner glow / secondary border of the focused tile / selection. */
    val tileSelectedInner: Color,
    /** Footer / controller command bar background (preserved storefront). */
    val footerBackground: Color,
    /** Footer divider line above the command bar (preserved storefront). */
    val footerDivider: Color,
    /** Primary text colour (labels, breadcrumb, tile names). */
    val textPrimary: Color,
    /** Secondary / muted text (counts, sublabels). */
    val textSecondary: Color,
    /** Content area background — semi-transparent so the wave shows through. */
    val contentBackground: Color,
    /** Panel background for the category rail (preserved storefront). */
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
    backgroundDeep    = Color(0xFF0743A2),
    backgroundMid     = Color(0xFF128BC9),
    selectionGlow     = Color(0x297EE8FF),
    chromeTop         = Color(0xFF0743A2),
    chromeBottom      = Color(0xFF128BC9),
    chromeDivider     = Color(0xFF7EE8FF),
    categorySelected  = Color(0xFF006BC4),
    categorySelectedEdge = Color(0xFF7EE8FF),
    categoryInactive  = Color(0xFF0874BE),
    tileNormal        = Color(0xFF083880),
    tileSelected      = Color(0xFF0B4FAA),
    tileSelectedEdge  = Color(0xFF7EE8FF),
    tileSelectedInner = Color(0xFF4CCFFF),
    footerBackground  = Color(0xFF003C8F),
    footerDivider     = Color(0xFF68C9EB),
    textPrimary       = Color.White,
    textSecondary     = Color(0xFFD6EDF7),
    contentBackground = Color(0x00000000),
    railBackground    = Color(0x30004590),
    searchField       = Color(0xFF0A2E5A),
    searchBorder      = Color(0xFF68C9EB),
    overlayDim        = Color(0x99000000),
    menuPanel         = Color(0xF00A1E3D),
    menuRowSelected   = Color(0x347EE8FF),
    destructive       = Color(0xFFFF6B6B),
)

val LocalStorefrontColors = staticCompositionLocalOf { DefaultStorefrontColors }

// ── Contrast helpers ───────────────────────────────────────────────────────────
// relativeLuminance / contrastRatio / ensureReadable now live in TextLegibility.kt (same package,
// so every call site below is unchanged): Settings, the XMB and the font-color picker need the
// same math, and one engine is the only way they can agree.

/**
 * Resolve the hue the drawer should theme itself around.
 *
 * Preset XMB schemes all resolve `accentColor` to white (see XmbColorScheme.resolve), so reading
 * the accent literally would repaint every theme identically. The identity instead lives in the
 * wave color — which also drives the background anchors — so the accent is only trusted when it is
 * a genuine hue (a custom-theme override); a neutral accent falls back to the wave, and a neutral
 * wave to its gradient anchor.
 */
private fun resolveHueSource(accent: Color, wave: Color, backgroundBottom: Color): Color =
    when {
        accent.isVividHue() -> accent
        wave.isVividHue() -> wave
        else -> backgroundBottom
    }

/** A hue is "vivid" when its channels actually spread (not near-white/gray) and it is not
 *  effectively black (where a channel spread can also look large). */
private fun Color.isVividHue(): Boolean {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    return max - min >= 0.10f && max >= 0.30f
}

/**
 * Derive a [StorefrontColors] from the live [LocalPFPColors].
 *
 * Hue comes from [resolveHueSource]; the palette is built with the same accent-tint idiom as
 * [menuCursorEdge] (the hue pulled toward white for bright edges) rather than lerping a literal
 * PSP cyan toward the accent, so every preset — Silver Mono and Golden Amber included — visibly
 * changes the drawer while text keeps a contrast floor ([ensureReadable]).
 */
@Composable
fun deriveStorefrontColors(): StorefrontColors {
    val pfp = LocalPFPColors.current
    val hue = resolveHueSource(pfp.accentColor, pfp.waveColor, pfp.backgroundBottom)

    // Bright edge family — lerp(hue, white, …) is the menuCursorEdge idiom, tuned so lines and
    // underlines stay luminous on the saturated background whatever the hue.
    val accentEdge = lerp(hue, Color.White, 0.55f)
    val accentInner = lerp(hue, Color.White, 0.32f)

    val bgTop = pfp.backgroundTop
    val bgBottom = pfp.backgroundBottom

    // ── Background ── deep upper (header) region easing into a rich midtone (grid) region, at
    // ~0.88 alpha so the XMB wave reads through more strongly. No blur, no frosted glass, no blobs.
    val backgroundDeep = bgTop.copy(alpha = 0.88f)
    val backgroundMid = lerp(bgTop, bgBottom, 0.55f).copy(alpha = 0.88f)

    // ── Header chrome (preserved storefront) ──────────────────────────────
    val chromeTop = bgTop.copy(alpha = 0.96f)
    val chromeBottom = lerp(bgTop, bgBottom, 0.55f).copy(alpha = 0.96f)

    // ── Category rail (preserved storefront) ──────────────────────────────
    val categorySelected = lerp(bgBottom, hue, 0.35f).copy(alpha = 0.92f)
    val categoryInactive = bgTop.copy(alpha = 0.50f)

    // ── Tiles ─────────────────────────────────────────────────────────────
    val tileNormal = bgTop.copy(alpha = 0.85f)
    val tileSelected = lerp(bgTop, hue, 0.35f).copy(alpha = 0.95f)

    // ── Footer (preserved storefront command bar) ─────────────────────────
    val footerBackground = lerp(bgTop, Color.Black, 0.15f).copy(alpha = 0.98f)
    val footerDivider = lerp(hue, Color.White, 0.25f)

    // ── Contrast direction ────────────────────────────────────────────────
    // 3.0 rather than AA 4.5: the preset gradient is mid-tone by design (white on the classic PSP
    // blue is ~3.9:1), so the floor only catches genuinely pale washes — Silver Mono, Golden
    // Amber, Sakura — instead of repainting every theme. When white washes out, the palette
    // flips to its light direction as one: text goes to the black family (secondary picks a
    // lifted slate so hierarchy survives), the translucent glass surfaces (search field, menu
    // panel) become light glass, and the edge lines — which only need to *differ* from the
    // surface, not clear a text ratio — darken toward the hue so the tab underline and selection
    // borders stay visible on the pale background.
    val textPrimary = ensureReadable(Color.White, backgroundMid, 3.0f)
    val lightChrome = textPrimary == Color.Black
    val textSecondary = ensureReadable(
        fg = if (lightChrome) lerp(Color.Black, Color.White, 0.25f)
        else lerp(hue, Color.White, 0.72f),
        bg = backgroundMid,
        minContrast = 3.0f,
    )
    val edge = if (lightChrome) lerp(hue, Color.Black, 0.45f) else accentEdge
    val edgeInner = if (lightChrome) lerp(hue, Color.Black, 0.20f) else accentInner

    // ── Content / rail ────────────────────────────────────────────────────
    val contentBackground = Color(0x00000000)  // transparent — wave shows through
    val railBackground = bgTop.copy(alpha = 0.35f)

    // ── Search ────────────────────────────────────────────────────────────
    // The field and the menu panel are translucent "glass" that hosts role text, so on a pale hue
    // (lightChrome) they become light glass — dark text on the still-dark panel would be broken.
    val searchField = if (lightChrome) Color.White.copy(alpha = 0.30f)
    else lerp(bgTop, Color.Black, 0.35f).copy(alpha = 0.90f)
    val searchBorder = edge

    // ── Mini menu ─────────────────────────────────────────────────────────
    val overlayDim = Color(0x99000000)
    val menuPanel = if (lightChrome) Color.White.copy(alpha = 0.92f)
    else lerp(Color.Black, bgTop, 0.30f).copy(alpha = 0.96f)
    val menuRowSelected = edge.copy(alpha = 0.20f)

    return StorefrontColors(
        backgroundDeep      = backgroundDeep,
        backgroundMid       = backgroundMid,
        selectionGlow       = (if (lightChrome) lerp(hue, Color.Black, 0.55f)
        else lerp(hue, Color.White, 0.45f)).copy(alpha = 0.16f),
        chromeTop           = chromeTop,
        chromeBottom        = chromeBottom,
        chromeDivider       = edge,
        categorySelected    = categorySelected,
        categorySelectedEdge = edge,
        categoryInactive    = categoryInactive,
        tileNormal          = tileNormal,
        tileSelected        = tileSelected,
        tileSelectedEdge    = edge,
        tileSelectedInner   = edgeInner,
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
