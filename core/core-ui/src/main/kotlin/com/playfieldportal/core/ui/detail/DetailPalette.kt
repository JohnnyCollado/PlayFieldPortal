package com.playfieldportal.core.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.PFPColors
import com.playfieldportal.core.ui.theme.composite
import com.playfieldportal.core.ui.theme.contrastRatio
import com.playfieldportal.core.ui.theme.ensureReadable
import com.playfieldportal.core.ui.theme.storefrontColorsFor

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
)

/** Derive the page palette from the active theme colors. Pure: same theme, same palette. */
fun detailPaletteFor(pfp: PFPColors): DetailPalette {
    val drawer = storefrontColorsFor(pfp)
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
        textPrimary = drawer.textPrimary,
        textMuted = textMuted,
    )
}

/** Muted text keeps the App Drawer's own 3.0 floor. */
private const val MUTED_TEXT_CONTRAST = 3.0

// A single-entry cache: the theme only changes with the color scheme, but these are read on every
// recomposition of every row. One immutable pair behind one volatile field, so a reader can never
// see one theme's key with another theme's palette.
@Volatile
private var cached: Pair<PFPColors, DetailPalette>? = null

/** The active theme's detail palette. */
@Composable
@ReadOnlyComposable
fun detailPalette(): DetailPalette {
    val pfp = LocalPFPColors.current
    cached?.let { (theme, palette) -> if (theme == pfp) return palette }
    return detailPaletteFor(pfp).also { cached = pfp to it }
}

// ── Hero sizing ───────────────────────────────────────────────────────────────

/** The hero never shrinks past this: below it the artwork stops reading as a banner. */
val DetailHeroMinHeight: Dp = 120.dp

/**
 * Everything in the page's top band below the hero: the lead-in spacer, the gap under the hero, and
 * the icon tile beside Launch over the quick actions (the taller of the two columns), plus a small
 * margin so the actions' focus ring clears the footer's divider.
 */
val DetailHeroBandBelow: Dp = 16.dp + 18.dp + 124.dp + 8.dp

/** The one-line launch error or action message a page shows under its quick actions. */
val DetailActionMessageHeight: Dp = 20.dp

/**
 * The hero height that keeps the hero, Launch and the quick actions on screen together: the page
 * scrolls back to its top whenever Launch or a quick action is focused, so on a short landscape
 * screen (the AYN Thor is 468dp tall) a full-height hero would push the actions under the footer.
 *
 * [messageLine] reserves room for the message line under the actions while one is showing, so a
 * launch error cannot push the actions back under the footer.
 */
fun detailHeroHeightFor(viewport: Dp, messageLine: Boolean = false): Dp {
    val below = DetailHeroBandBelow + if (messageLine) DetailActionMessageHeight else 0.dp
    return (viewport - below).coerceIn(DetailHeroMinHeight, DetailHeroHeight)
}
