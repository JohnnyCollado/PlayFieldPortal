package com.playfieldportal.core.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

data class PFPColors(
    val waveColor: Color,
    val accentColor: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val backgroundOverlay: Color,
    val selectedItem: Color,
    val categoryBar: Color,
    // Background gradient anchors behind the wave (top → bottom).
    val backgroundTop: Color = Color(0xFF26106C),
    val backgroundBottom: Color = Color(0xFF3B148C),
    // Unified tint for the XMB's silhouette icon art (catbar_*/sysicon_*), applied via
    // PortalIcon. White = the icons' native color, i.e. visually a no-op default.
    // See docs/icon-system-plan.md.
    val iconColor: Color = Color.White,
)

val LocalPFPColors = staticCompositionLocalOf {
    DefaultPFPColors
}

val DefaultPFPColors = PFPColors(
    waveColor         = Color(0xFF0055AA),
    accentColor       = Color(0xFFFFFFFF),
    textPrimary       = Color(0xFFFFFFFF),
    textSecondary     = Color(0xFFCCDDFF),
    backgroundOverlay = Color(0x88000000),
    selectedItem      = Color(0xFFFFFFFF),
    categoryBar       = Color(0x00000000),
    // Classic PSP "Original" blue gradient, sampled from the real XMB wave video: a saturated azure
    // top easing to a brighter cyan-blue near the wave (not a washed-out sky-blue).
    backgroundTop     = Color(0xFF0743A2),
    backgroundBottom  = Color(0xFF128BC9),
)

/**
 * The single source for the app's flat UI palette. The Material scheme below and the settings
 * screens (SettingsScaffold's SettingsAccent/SettingsSubtext/SettingsDivider) both draw from
 * here, so an accent or surface change propagates everywhere at once.
 */
object PfpPalette {
    val Accent         = Color(0xFF4A90D9)
    val Subtext        = Color(0xFFAAAAAA)
    val Divider        = Color(0xFF2A2A2A)
    val SurfaceDim     = Color(0xFF10141C)
    val Surface        = Color(0xFF141A24)
    val SurfaceMid     = Color(0xFF181F2B)
    val SurfaceHigh    = Color(0xFF1B2230)
    val SurfaceHighest = Color(0xFF202838)
    val Outline        = Color(0xFF3A4356)
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
    CompositionLocalProvider(LocalPFPColors provides colors) {
        MaterialTheme(colorScheme = PfpDarkColorScheme, content = content)
    }
}

object PFPThemeTokens {
    val colors: PFPColors
        @Composable get() = LocalPFPColors.current
}
