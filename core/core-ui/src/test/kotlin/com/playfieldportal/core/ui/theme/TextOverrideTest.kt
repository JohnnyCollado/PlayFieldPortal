package com.playfieldportal.core.ui.theme

import androidx.compose.ui.graphics.Color
import com.playfieldportal.core.domain.model.lightBackgroundAnchors
import com.playfieldportal.core.ui.detail.detailPaletteFor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The user's Main / Sub font colours reaching the palettes. Two promises: with neither set every
 * colour is exactly what the theme derives on its own, and with one set only the text roles move —
 * to the picked colour, unclamped, at the weight the role carries — never a surface or an icon.
 */
class TextOverrideTest {

    private fun scheme(wave: Long): PFPColors {
        val (top, bottom) = lightBackgroundAnchors(wave)
        return PFPColors(
            waveColor = Color(wave),
            accentColor = Color.White,
            textPrimary = Color.White,
            textSecondary = Color.White.copy(alpha = 0.7f),
            backgroundOverlay = Color(0x88000000),
            selectedItem = Color.White,
            categoryBar = Color(0x00000000),
            backgroundTop = Color(top),
            backgroundBottom = Color(bottom),
        )
    }

    // Classic blue (white text) and silver (the drawer flips to black text).
    private val themes = listOf(scheme(0xFF0055AA), scheme(0xFFB8C4D0))
    private val orange = Color(0xFFFF8800)
    private val teal = Color(0xFF33CCAA)

    // ── textOr / subTextOr ──────────────────────────────────────────────────

    @Test
    fun `no font colour returns the site's own colour untouched`() {
        val pfp = themes[0]
        val inactive = Color(0xCCD8E6FF)
        assertEquals(inactive, pfp.textOr(inactive))
        assertEquals(inactive, pfp.subTextOr(inactive))
        assertEquals(Color(0xFFD6EDF7), pfp.subTextOr(Color(0xFFD6EDF7), weight = 0.72f))
    }

    @Test
    fun `the main colour takes the site's alpha`() {
        val pfp = themes[0].copy(textOverride = orange)
        assertEquals(orange, pfp.textOr(Color.White))
        assertEquals(orange.copy(alpha = 0xCC / 255f), pfp.textOr(Color(0xCCD8E6FF)))
        assertEquals(orange.copy(alpha = 0.72f), pfp.textOr(Color(0xFFB9C6DC), weight = 0.72f))
    }

    @Test
    fun `sub text uses the sub colour, else the main colour, at the site's alpha`() {
        val subtitle = Color(0xAAC8DAF2)
        assertEquals(orange.copy(alpha = 0xAA / 255f), themes[0].copy(textOverride = orange).subTextOr(subtitle))
        assertEquals(
            teal.copy(alpha = 0xAA / 255f),
            themes[0].copy(textOverride = orange, subTextOverride = teal).subTextOr(subtitle),
        )
        assertEquals(teal.copy(alpha = 0xAA / 255f), themes[0].copy(subTextOverride = teal).subTextOr(subtitle))
        // Main text never takes the sub colour.
        assertEquals(Color.White, themes[0].copy(subTextOverride = teal).textOr(Color.White))
    }

    @Test
    fun `dimming keeps a translucent role below its base`() {
        val sub = orange.copy(alpha = 0.72f)
        assertEquals(0.72f * 0.6f, sub.dimmed(0.6f).alpha, 1f / 255f)
        assertEquals(Color.White.copy(alpha = 0.6f), Color.White.dimmed(0.6f))
    }

    // ── Storefront palette (App Drawer, App / Game Picker) ──────────────────

    @Test
    fun `storefront without a font colour derives its text as before`() {
        for (pfp in themes) {
            val sf = storefrontColorsFor(pfp)
            val backgroundMid = backgroundMidOf(pfp)
            assertEquals(ensureReadable(Color.White, backgroundMid, 3.0f), sf.textPrimary)
            assertEquals(sf.textPrimary, sf.iconPrimary)
            assertEquals(sf.textSecondary, sf.iconSecondary)
        }
    }

    @Test
    fun `storefront text becomes the font colours and nothing else moves`() {
        for (pfp in themes) {
            val plain = storefrontColorsFor(pfp)
            // Black would flip a dark theme's chrome if the pick decided the direction.
            for (picked in listOf(orange, Color.Black)) {
                val main = storefrontColorsFor(pfp.copy(textOverride = picked))
                assertEquals(picked, main.textPrimary)
                assertEquals(picked.copy(alpha = SECONDARY_TEXT_WEIGHT), main.textSecondary)
                assertEquals(plain.withoutText(), main.withoutText())

                val both = storefrontColorsFor(pfp.copy(textOverride = picked, subTextOverride = teal))
                assertEquals(picked, both.textPrimary)
                assertEquals(teal.copy(alpha = SECONDARY_TEXT_WEIGHT), both.textSecondary)
                assertEquals(plain.withoutText(), both.withoutText())
            }
        }
    }

    // ── Detail palette (Game Detail, Shiba Coins, Player Status, …) ─────────

    @Test
    fun `detail palette without a font colour keeps text and icons together`() {
        for (pfp in themes) {
            val p = detailPaletteFor(pfp)
            assertEquals(p.iconPrimary, p.textPrimary)
            assertEquals(p.iconMuted, p.textMuted)
            assertEquals(storefrontColorsFor(pfp).textPrimary, p.textPrimary)
        }
    }

    @Test
    fun `detail text becomes the font colours and nothing else moves`() {
        for (pfp in themes) {
            val plain = detailPaletteFor(pfp)
            for (picked in listOf(orange, Color.Black)) {
                val main = detailPaletteFor(pfp.copy(textOverride = picked))
                assertEquals(picked, main.textPrimary)
                assertEquals(picked.copy(alpha = SECONDARY_TEXT_WEIGHT), main.textMuted)
                assertEquals(plain.copy(textPrimary = main.textPrimary, textMuted = main.textMuted), main)

                val both = detailPaletteFor(pfp.copy(textOverride = picked, subTextOverride = teal))
                assertEquals(teal.copy(alpha = SECONDARY_TEXT_WEIGHT), both.textMuted)
                assertEquals(plain.copy(textPrimary = both.textPrimary, textMuted = both.textMuted), both)
            }
        }
    }

    /** The storefront's mid-tone the text is checked against (see storefrontColorsFor). */
    private fun backgroundMidOf(pfp: PFPColors): Color =
        androidx.compose.ui.graphics.lerp(pfp.backgroundTop, pfp.backgroundBottom, 0.55f).copy(alpha = 0.88f)

    /** Everything but the two text roles. */
    private fun StorefrontColors.withoutText() = copy(textPrimary = Color.Unspecified, textSecondary = Color.Unspecified)
}
