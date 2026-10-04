package com.playfieldportal.core.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

// The theme's colours and the text-role rule, shared by the launcher (core-ui provides them through
// LocalPFPColors and the themedText wrappers) and the Theme Studio's preview, which runs these same
// functions rather than a copy.

@Immutable
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
    // The user's Display ▸ Font Colour, exact and unclamped; null when none is set. Separate from
    // textPrimary (which falls back to the theme's white) so screens that compute their own text
    // colour — the crossbar's constants, the drawer/detail palettes — can tell "the user picked
    // white" from "nobody picked anything" and stay pixel-identical in the latter case.
    val textOverride: Color? = null,
    // The user's Sub Font Colour, for secondary text (subtitles, sublabels, values, muted text);
    // null when none is set, in which case secondary text follows [textOverride] instead.
    val subTextOverride: Color? = null,
)

/**
 * Main text: [default] repainted in the user's font colour when one is set, else [default]
 * untouched. The colour takes [default]'s alpha (times its own), so a site's hierarchy survives the
 * repaint: White titles become the colour, 0xCC unselected labels the colour at 0xCC.
 */
fun PFPColors.textOr(default: Color): Color = textOr(default, default.alpha)

/**
 * As [textOr], for an opaque [default] whose weight is carried by its tone rather than its alpha
 * (a grey sublabel, a palette's muted text): the colour is drawn at [weight] instead.
 */
fun PFPColors.textOr(default: Color, weight: Float): Color = repaint(textOverride, default, weight)

/**
 * Sub text (subtitles, sublabels, values, muted text): as [textOr], but the Sub Font Colour wins
 * when set, and the main font colour stands in for it when only that one is.
 */
fun PFPColors.subTextOr(default: Color): Color = subTextOr(default, default.alpha)

/** [subTextOr] at an explicit [weight], for an opaque [default] (see [textOr]). */
fun PFPColors.subTextOr(default: Color, weight: Float): Color =
    repaint(subTextOverride ?: textOverride, default, weight)

private fun repaint(picked: Color?, default: Color, weight: Float): Color =
    picked?.let { it.copy(alpha = it.alpha * weight) } ?: default

/**
 * This colour at [fraction] of its own alpha. For dimming a text role that may itself be
 * translucent (the user's font colour at a secondary weight), where `copy(alpha = …)` would replace
 * that weight and lift the dimmed step above its own base. Identical to `copy` on an opaque colour.
 */
fun Color.dimmed(fraction: Float): Color = copy(alpha = alpha * fraction)

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
