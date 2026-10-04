package com.playfieldportal.studio.preview.screens

import androidx.compose.ui.graphics.Color
import com.playfieldportal.studio.StudioState
import com.playfieldportal.studio.TextColorChoice
import com.playfieldportal.studio.preview.toPreviewModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The screen palettes carry the theme's text colours exactly as the launcher builds them
 * (storefrontColorsFor / detailPaletteFor): Main replaces primary text, Sub (else Main) the
 * secondary / muted text at the 0.72 weight, an unselected label is Main at 0.72, and the icon tones
 * stay the palette's own.
 */
class PaletteTextColorTest {

    private val orange = Color(0xFFFF8800)
    private val teal = Color(0xFF00AA88)

    private fun model(main: Int? = null, sub: Int? = null) = StudioState(
        textColor = main?.let { TextColorChoice.Custom(it) } ?: TextColorChoice.Auto,
        subTextColor = sub?.let { TextColorChoice.Custom(it) } ?: TextColorChoice.Auto,
    ).toPreviewModel()

    @Test
    fun `the weighted rule draws the picked colour at the given weight`() {
        val m = model(main = 0xFFFF8800.toInt())
        assertEquals(orange.copy(alpha = 0.72f), m.textOr(Color.Gray, 0.72f))
        assertEquals(orange.copy(alpha = 0.72f), m.subTextOr(Color.Gray, 0.72f), "sub falls back to main")
        assertEquals(Color.Gray, model().textOr(Color.Gray, 0.72f))
    }

    @Test
    fun `the drawer palette themes its text and keeps its icon tones`() {
        val plain = drawerPalette(model())
        val themed = drawerPalette(model(main = 0xFFFF8800.toInt(), sub = 0xFF00AA88.toInt()))
        assertEquals(orange, themed.textPrimary)
        assertEquals(teal.copy(alpha = 0.72f), themed.textSecondary)
        assertEquals(orange.copy(alpha = 0.72f), themed.unselectedLabel)
        assertEquals(plain.textPrimary, themed.iconPrimary)
        assertEquals(plain.textSecondary, themed.iconSecondary)
        assertEquals(plain.textSecondary, plain.unselectedLabel, "unthemed, an unselected label is the secondary text")
    }

    @Test
    fun `the options panel sits on the full-screen backdrop, not the raw accent`() {
        // A bright accent (the FF7 theme's mint) made the old wave-colour panel lighter than the
        // screens it opened over; the panel now uses the drawer's own deep-to-mid backdrop.
        val m = StudioState(accentArgb = 0xFF40FFC2.toInt()).toPreviewModel()
        val sf = drawerPalette(m)
        assertEquals(listOf(sf.backgroundDeep, sf.backgroundMid), com.playfieldportal.studio.preview.optionsPanelBackdrop(m))
    }

    @Test
    fun `the detail palette themes its text and keeps its icon tones`() {
        val plain = detailPreviewPaletteFor(model())
        val themed = detailPreviewPaletteFor(model(main = 0xFFFF8800.toInt(), sub = 0xFF00AA88.toInt()))
        assertEquals(orange, themed.textPrimary)
        assertEquals(teal.copy(alpha = 0.72f), themed.textMuted)
        assertEquals(orange.copy(alpha = 0.72f), themed.unselectedLabel)
        assertEquals(plain.textPrimary, themed.iconPrimary)
        assertEquals(plain.textMuted, themed.iconMuted)
        assertEquals(plain.iconMuted, plain.unselectedLabel)
    }
}
