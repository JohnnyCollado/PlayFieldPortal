package com.playfieldportal.core.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** [PFPColors.textOr] against the active theme. */
@Composable
@ReadOnlyComposable
fun themedText(default: Color): Color = LocalPFPColors.current.textOr(default)

/** [PFPColors.subTextOr] against the active theme. */
@Composable
@ReadOnlyComposable
fun themedSubText(default: Color): Color = LocalPFPColors.current.subTextOr(default)

/**
 * [PFPColors.textOr] at an explicit weight, against the active theme. For sites whose dimmed text
 * is an opaque grey-blue (#B9C6DC and kin) rather than a translucent white; they pass the
 * palettes' [SECONDARY_TEXT_WEIGHT] so every screen's secondary step matches.
 */
@Composable
@ReadOnlyComposable
fun themedText(default: Color, weight: Float): Color = LocalPFPColors.current.textOr(default, weight)

/** [PFPColors.subTextOr] at an explicit weight, against the active theme. */
@Composable
@ReadOnlyComposable
fun themedSubText(default: Color, weight: Float): Color = LocalPFPColors.current.subTextOr(default, weight)

val LocalPFPColors = staticCompositionLocalOf {
    DefaultPFPColors
}

// Dark Material scheme derived from the app's palette. Any stock M3 component that doesn't set
// explicit colors (AlertDialogs, TextButtons, text fields inside dialogs) inherits this, so
// system prompts match the XMB theme instead of falling back to Material's light purple.
private val PfpDarkColorScheme = darkColorScheme(
    primary              = PfpPalette.Accent,
    onPrimary            = Color.White,
    secondary            = PfpPalette.Accent,
    onSecondary          = Color.White,
    background           = PfpPalette.SurfaceDim,
    onBackground         = Color.White,
    surface              = PfpPalette.Surface,
    onSurface            = Color.White,
    surfaceVariant       = PfpPalette.SurfaceHigh,
    onSurfaceVariant     = PfpPalette.Subtext,
    // AlertDialog containers draw from the surfaceContainer roles.
    surfaceContainerLowest  = PfpPalette.SurfaceDim,
    surfaceContainerLow     = PfpPalette.Surface,
    surfaceContainer        = PfpPalette.SurfaceMid,
    surfaceContainerHigh    = PfpPalette.SurfaceHigh,
    surfaceContainerHighest = PfpPalette.SurfaceHighest,
    outline              = PfpPalette.Outline,
    outlineVariant       = PfpPalette.Divider,
)

@Composable
fun PFPTheme(
    colors: PFPColors = DefaultPFPColors,
    content: @Composable () -> Unit,
) {
    // The theme's text roles, resolved once per theme change. Today [colors.textPrimary] is white
    // on every path (XmbPalette.textColor is hardcoded white, DefaultPFPColors matches), so this
    // is a no-op seam — which is the point: it lands with no visible change, and the user's font
    // colour and the measured-backdrop clamp arrive through it later without touching call sites.
    //
    // Only `primary` is taken from the theme. `secondary` deliberately stays PfpPalette.Subtext:
    // PFPColors.textSecondary is textPrimary at 0.7 alpha, so adopting it here would repaint every
    // sublabel in the app from #AAAAAA to translucent white — a real visual change, smuggled in
    // under a refactor. Once the user picks font colours, secondary (Sub) and inactive (Main,
    // dimmed) follow them at the weight they carry today: #AAAAAA is white at 0xAA, and inactive's
    // own alpha is 0xCC.
    val textColors = remember(colors.textPrimary, colors.textOverride, colors.subTextOverride) {
        pfpTextColorsFor(colors)
    }

    CompositionLocalProvider(
        LocalPFPColors provides colors,
        LocalPfpTextColors provides textColors,
        // Every Text() that does not pass an explicit color= reads LocalContentColor, so providing
        // it here is the one line that reaches the long tail of call sites without touching them.
        LocalContentColor provides textColors.primary,
    ) {
        MaterialTheme(colorScheme = PfpDarkColorScheme, content = content)
    }
}

object PFPThemeTokens {
    val colors: PFPColors
        @Composable get() = LocalPFPColors.current
}
