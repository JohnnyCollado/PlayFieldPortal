package com.playfieldportal.core.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp

// The one menu-cursor treatment every menu shares, derived from the active theme's accent color so
// the highlight follows the chosen color scheme. Both colors are blended toward white before use:
// menu panels are dark, so even a dark theme accent stays clearly visible (and the default white
// accent renders as a bright neutral highlight).

/** Fill behind the focused menu row. */
@Composable
fun menuCursorFill(): Color =
    lerp(LocalPFPColors.current.accentColor, Color.White, 0.20f).copy(alpha = 0.34f)

/** Bright edge/border of the focused menu row — the part that makes the cursor unmistakable. */
@Composable
fun menuCursorEdge(): Color =
    lerp(LocalPFPColors.current.accentColor, Color.White, 0.55f).copy(alpha = 0.95f)

/**
 * The shared focus-cursor for menu rows: accent-tinted fill plus a bright border. No-op when
 * [selected] is false, so it can sit unconditionally in a row's modifier chain.
 */
@Composable
fun Modifier.menuCursor(selected: Boolean, shape: Shape = RoundedCornerShape(8.dp)): Modifier {
    if (!selected) return this
    return this
        .clip(shape)
        .background(menuCursorFill())
        .border(1.5.dp, menuCursorEdge(), shape)
}
