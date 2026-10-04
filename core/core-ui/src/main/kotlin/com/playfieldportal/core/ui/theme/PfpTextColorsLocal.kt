package com.playfieldportal.core.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * `staticCompositionLocalOf`, matching [LocalPFPColors] and `LocalIconLegibility`: the value only
 * changes on a settings edit, so paying a full-subtree recomposition then is the right trade
 * against reading it on every frame.
 */
val LocalPfpTextColors = staticCompositionLocalOf { DefaultPfpTextColors }
