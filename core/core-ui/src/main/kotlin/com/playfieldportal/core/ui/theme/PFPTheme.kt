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
    // Classic PSP "Original" blue gradient — deep navy at the top, bright sky-blue at the bottom.
    backgroundTop     = Color(0xFF0C2A78),
    backgroundBottom  = Color(0xFF52A6E0),
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
