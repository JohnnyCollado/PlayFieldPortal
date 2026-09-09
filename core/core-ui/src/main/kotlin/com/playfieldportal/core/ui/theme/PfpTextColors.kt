package com.playfieldportal.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * How a text run separates itself from the pixels behind it, *after* the engine has measured them.
 *
 * This is the resolved outcome, not the user's preference — `TextLegibilityStyle` is the request,
 * this is the answer. The distinction matters because AUTO has to be able to reply "nothing"
 * ([None]) over a dark wallpaper and "a plate at 0.38" over a bright one, from the same setting.
 */
@Immutable
sealed interface TextProtection {
    /** The fill already clears its threshold. */
    data object None : TextProtection

    /** The standard directional drop shadow — the always-on floor, and today's rendering. */
    data object Shadow : TextProtection

    /**
     * A text-shaped rounded plate of [color] at a *solved* [alpha], drawn in the label's own
     * `drawBehind`. One extra draw op and zero extra layout nodes, which is what makes it usable
     * inside a `LazyColumn` where a second `Text` node would not be.
     */
    @Immutable
    data class Plate(val color: Color, val alpha: Float) : TextProtection

    /**
     * A stroked copy behind the fill. Costs a second `Text` node because `TextStyle.drawStyle`
     * *replaces* the fill rather than adding to it, so this is for the few large non-list labels.
     */
    @Immutable
    data class Outline(val color: Color) : TextProtection
}

/**
 * The resolved text palette for the current subtree — the seam every text color in the app reads
 * through.
 *
 * There is deliberately no `PfpText` composable. Roughly 400 `Text(...)` call sites funnel through
 * about twenty module-level color constants, and `Text` already reads `LocalContentColor`, so
 * re-pointing those constants at this CompositionLocal reaches every site without touching them —
 * and cannot be bypassed by call site 401 the way a wrapper composable can.
 *
 * [requested], [adjusted] and [achievedRatio] describe [primary] only. They exist so the settings
 * screen can say *why* the color on screen is not exactly the one the user picked, which is the
 * difference between an adjustment and a bug.
 */
@Immutable
data class PfpTextColors(
    /** Labels and body copy. Resolved and, when necessary, lightness-clamped. */
    val primary: Color,
    /** Sublabels, counts, helper text. */
    val secondary: Color,
    /** Rows that are present but not selectable. */
    val inactive: Color,
    /** Remove / Delete / Uninstall. Never the accent — see [StorefrontColors.destructive]. */
    val destructive: Color,
    /** What the user actually picked, before any clamp. */
    val requested: Color,
    /** `primary != requested` — this is what raises the user-facing notice. */
    val adjusted: Boolean,
    /** Contrast of [primary] against the backdrop it was resolved for. */
    val achievedRatio: Float,
    /** What, if anything, has to be drawn behind the text for [primary] to hold up. */
    val protection: TextProtection,
)

/**
 * White text with the standard drop shadow — exactly what the app renders today, so providing this
 * local is a no-op until a user color or a measured backdrop says otherwise.
 */
val DefaultPfpTextColors = PfpTextColors(
    primary = Color.White,
    secondary = PfpPalette.Subtext,
    inactive = Color(0xCCD8E6FF),
    destructive = Color(0xFFFF6B6B),
    requested = Color.White,
    adjusted = false,
    achievedRatio = 21f,
    protection = TextProtection.Shadow,
)

/**
 * `staticCompositionLocalOf`, matching [LocalPFPColors] and `LocalIconLegibility`: the value only
 * changes on a settings edit, so paying a full-subtree recomposition then is the right trade
 * against reading it on every frame.
 */
val LocalPfpTextColors = staticCompositionLocalOf { DefaultPfpTextColors }
