package com.playfieldportal.core.ui.detail

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.ui.theme.LocalPFPColors
import com.playfieldportal.core.ui.theme.PFPColors

// The detail palette's composition side and the page's hero sizing: the palette itself and its
// derivation live in theme-render (DetailPalette.kt), shared with the Theme Studio's preview.

/**
 * An unselected primary label (a tab, a disabled action): [DetailPalette.textMuted] by default.
 * Once the user sets a font colour it is Main text dimmed — that colour at the secondary weight —
 * not the Sub colour, which belongs to secondary text.
 */
@Composable
@ReadOnlyComposable
fun DetailPalette.unselectedLabel(): Color = unselectedLabel(LocalPFPColors.current)

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
