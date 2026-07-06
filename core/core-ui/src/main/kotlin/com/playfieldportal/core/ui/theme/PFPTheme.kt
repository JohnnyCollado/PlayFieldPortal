package com.playfieldportal.core.ui.theme

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

@Composable
fun PFPTheme(
    colors: PFPColors = DefaultPFPColors,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalPFPColors provides colors,
        content = content,
    )
}

object PFPThemeTokens {
    val colors: PFPColors
        @Composable get() = LocalPFPColors.current
}
