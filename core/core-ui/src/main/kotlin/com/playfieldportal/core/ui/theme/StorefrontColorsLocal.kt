package com.playfieldportal.core.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// The storefront palette's composition side: the palette itself and its derivation live in
// theme-render (StorefrontColors.kt), shared with the Theme Studio's preview.

val LocalStorefrontColors = staticCompositionLocalOf { DefaultStorefrontColors }

/**
 * An unselected primary label (a tile name, a menu option): [StorefrontColors.textSecondary] by
 * default. Once the user sets a font colour it is Main text dimmed — that colour at the secondary
 * weight — not the Sub colour, which belongs to secondary text.
 */
@Composable
@ReadOnlyComposable
fun StorefrontColors.unselectedLabel(): Color = unselectedLabel(LocalPFPColors.current)

/**
 * Derive a [StorefrontColors] from the live [LocalPFPColors].
 *
 * Hue comes from [resolveHueSource]; the palette is built with the same accent-tint idiom as
 * [menuCursorEdge] (the hue pulled toward white for bright edges) rather than lerping a literal
 * PSP cyan toward the accent, so every preset — Silver Mono and Golden Amber included — visibly
 * changes the drawer while text keeps a contrast floor ([ensureReadable]).
 */
@Composable
fun deriveStorefrontColors(): StorefrontColors = storefrontColorsFor(LocalPFPColors.current)
