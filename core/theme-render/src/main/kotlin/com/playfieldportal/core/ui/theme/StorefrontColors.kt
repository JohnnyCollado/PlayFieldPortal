package com.playfieldportal.core.ui.theme

import androidx.compose.runtime.Immutable
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
    /**
     * Drawn glyphs and lines that wear the text tones (the search lens, progress tracks): the
     * theme's own [textPrimary] / [textSecondary], which the user's font colours never replace —
     * icons are not text. Equal to the text roles while no font colour is set.
     */
    val iconPrimary: Color,
    val iconSecondary: Color,
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
 * How far secondary text sits from primary, as an alpha for the user's font colour: the derived
 * secondary is the hue lifted 72% of the way to white, so the picked colour at 0.72 over the same
 * hued surface lands the same step below primary.
 */
const val SECONDARY_TEXT_WEIGHT = 0.72f

/**
 * An unselected primary label (a tile name, a menu option) under [pfp]: [StorefrontColors.textSecondary]
 * by default. Once the user sets a font colour it is Main text dimmed — that colour at the secondary
 * weight — not the Sub colour, which belongs to secondary text.
 */
fun StorefrontColors.unselectedLabel(pfp: PFPColors): Color = pfp.textOr(textSecondary, SECONDARY_TEXT_WEIGHT)

/**
 * The pure derivation behind core-ui's `deriveStorefrontColors`, for callers that already hold the
 * theme colors (the detail page's palette, which must match the App Drawer exactly, the Theme
 * Studio's preview) and for tests.
 */
fun storefrontColorsFor(pfp: PFPColors): StorefrontColors {
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
        // The user's font colours, when set, replace the text roles unclamped (Main → primary, Sub
        // → secondary). lightChrome above stays decided by the readable white/black, so a picked
        // colour never restyles surfaces.
        textPrimary         = pfp.textOr(textPrimary, weight = 1f),
        textSecondary       = pfp.subTextOr(textSecondary, weight = SECONDARY_TEXT_WEIGHT),
        iconPrimary         = textPrimary,
        iconSecondary       = textSecondary,
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
