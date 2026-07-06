package com.playfieldportal.themekit

import kotlinx.serialization.Serializable

/**
 * Per-theme XMB geometry. The defaults are the values pixel-tuned against a real-PSP theme
 * capture (see commit "XMB: align cross layout with authentic PSP theme geometry"); a theme
 * whose wallpaper draws its own cross/bands can override them so the XMB lands on the art.
 *
 * The Theme Studio companion can derive [barTopFraction] automatically by detecting the
 * wallpaper's dark cross-band, the same measurement used to tune these defaults.
 */
@Serializable
data class XmbLayoutSpec(
    /** Top of the category bar as a fraction of the XMB content height. */
    val barTopFraction: Float = 0.11f,
    /** Content inset below the status strip, dp. */
    val contentTopPaddingDp: Float = 20f,
    /** Category icon size, dp: selected / unselected. */
    val categoryIconSelectedDp: Float = 72f,
    val categoryIconDp: Float = 56f,
    /** First-level item leading icon glyph / centering slot, dp. */
    val itemIconDp: Float = 62f,
    val itemIconSlotDp: Float = 74f,
    /** First-level item title text, sp: selected / unselected. */
    val itemTextSelectedSp: Float = 22f,
    val itemTextSp: Float = 18f,
    /** Gap between the item icon column and its label, dp. */
    val itemTextStartGapDp: Float = 14f,
    /** Extra left shift of the whole cross beyond one category slot width, dp. */
    val leftAnchorExtraDp: Float = 6f,
    /** How far the dissolving previous item rises above the bar, in row heights. */
    val previousItemRiseRows: Float = 0.5f,
) {
    companion object {
        val DEFAULT = XmbLayoutSpec()
    }
}
