package com.playfieldportal.core.ui.detail

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.playfieldportal.core.ui.theme.PFPColors
import com.playfieldportal.core.ui.theme.SECONDARY_TEXT_WEIGHT
import com.playfieldportal.core.ui.theme.composite
import com.playfieldportal.core.ui.theme.contrastRatio
import com.playfieldportal.core.ui.theme.ensureReadable
import com.playfieldportal.core.ui.theme.storefrontColorsFor
import com.playfieldportal.core.ui.theme.subTextOr
import com.playfieldportal.core.ui.theme.textOr

// ── Accent shading for the console-style detail page ──────────────────────────
//
// The page wears the App Drawer's colors exactly — the same theme gradient at the same 0.88 alpha,
// so the XMB wave reads through both screens identically — with the approved mockup's layering on
// top (docs/mockup/game-detail-screen.png): information rows a translucent step darker than the
// page, a lifted edge, and one bright accent for the controller cursor and progress.

@Immutable
data class DetailPalette(
    val pageTop: Color,
    val pageBottom: Color,
    /** The pinned breadcrumb band: see-through, like the App Drawer's header. */
    val header: Color,
    /** The pinned helper-footer band: see-through, like the App Drawer's footer. */
    val footer: Color,
    /** The thin line under the breadcrumb and above the footer. */
    val divider: Color,
    /** Row, quick-action and tile fill. */
    val rowFill: Color,
    /** The resting edge of rows and tiles. */
    val rowEdge: Color,
    /** Progress bar track. */
    val track: Color,
    /** The controller cursor edge, focused labels and progress fill. */
    val focus: Color,
    val textPrimary: Color,
    val textMuted: Color,
    /**
     * Icon tints and the page's light/dark direction: the theme's own text colours, which the
     * user's font colour never replaces — icons are not text, and a picked colour must not
     * restyle surfaces. Equal to [textPrimary] / [textMuted] while no font colour is set.
     */
    val iconPrimary: Color,
    val iconMuted: Color,
)

/** Derive the page palette from the active theme colors. Pure: same theme, same palette. */
fun detailPaletteFor(pfp: PFPColors): DetailPalette {
    // Everything is derived without the user's font colours first, so the row glass and the muted
    // text's contrast repair see the theme's own text; the colours then replace the text roles
    // unclamped, muted (Sub) at the drawer's secondary weight.
    val drawer = storefrontColorsFor(pfp.copy(textOverride = null, subTextOverride = null))
    // A pale theme flips the drawer to dark text; dark rows would then bury it, so rows turn to
    // light glass the way the drawer's search field and menu panel do.
    val lightChrome = drawer.textPrimary == Color.Black
    val rowFill = if (lightChrome) Color.White.copy(alpha = 0.30f)
    else lerp(pfp.backgroundTop, Color.Black, 0.30f).copy(alpha = 0.60f)
    // The drawer checks its secondary text against its mid-tone gradient, and on a mid-bright hue
    // (Sunset Orange) that check flips it to black — which a row darker than the gradient buries.
    // Muted text is read on rows here, so it is checked where it lands: a row over the page, over
    // the brightest wave. When the drawer's choice fails there, it is the primary text, dimmed.
    val rowOnScreen = composite(rowFill, composite(drawer.backgroundDeep, Color.White))
    val textMuted = if (contrastRatio(drawer.textSecondary, rowOnScreen) >= MUTED_TEXT_CONTRAST) {
        drawer.textSecondary
    } else {
        ensureReadable(lerp(drawer.textPrimary, rowOnScreen, 0.18f), rowOnScreen, MUTED_TEXT_CONTRAST.toFloat())
    }
    return DetailPalette(
        pageTop = drawer.backgroundDeep,
        pageBottom = drawer.backgroundMid,
        header = Color.Transparent,
        footer = Color.Transparent,
        divider = drawer.chromeDivider,
        rowFill = rowFill,
        rowEdge = drawer.chromeDivider.copy(alpha = 0.35f),
        track = drawer.chromeDivider.copy(alpha = 0.25f),
        focus = drawer.tileSelectedEdge,
        textPrimary = pfp.textOr(drawer.textPrimary, weight = 1f),
        textMuted = pfp.subTextOr(textMuted, weight = SECONDARY_TEXT_WEIGHT),
        iconPrimary = drawer.textPrimary,
        iconMuted = textMuted,
    )
}

/**
 * An unselected primary label (a tab, a disabled action) under [pfp]: [DetailPalette.iconMuted] by
 * default. Once the user sets a font colour it is Main text dimmed — that colour at the secondary
 * weight — not the Sub colour, which belongs to secondary text.
 */
fun DetailPalette.unselectedLabel(pfp: PFPColors): Color = pfp.textOr(iconMuted, SECONDARY_TEXT_WEIGHT)

/** Muted text keeps the App Drawer's own 3.0 floor. */
private const val MUTED_TEXT_CONTRAST = 3.0
