package com.playfieldportal.core.ui.theme

import androidx.compose.ui.graphics.Color
import com.playfieldportal.themekit.ColorCascade
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the text-legibility engine. Pure color math — no Compose runtime, no Robolectric.
 *
 * The first test here is the one that would have caught the shipped bug: the Settings scrim was
 * built from `ColorCascade`'s gradient anchors and nobody ever asserted that white text survives
 * them, so a `lighten()` retune quietly pushed "Reset Sound to Defaults" down to 1.82:1.
 */
class TextLegibilityTest {

    /** Classic Blue's wave hue — the preset the measured screenshot was taken on. */
    private val classicBlueWave = 0xFF0055AAL

    private fun argb(v: Long) = Color(v.toInt())

    /** A neutral grey with the given WCAG relative luminance, for constructing measured bands. */
    private fun greyAtLuminance(target: Double): Color {
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2f
            if (relativeLuminance(Color(mid, mid, mid)) < target) lo = mid else hi = mid
        }
        return Color(hi, hi, hi)
    }

    // ── The drift pin ─────────────────────────────────────────────────────

    @Test
    fun `the raw bottom gradient anchor cannot carry white text`() {
        val bottom = argb(ColorCascade.lightBackgroundAnchors(classicBlueWave).second)
        assertTrue(
            "lighten(wave, 0.28) is meant to be a bright anchor; if this ever passes 4.5:1 on its " +
                "own the scrim solve below has stopped being load-bearing and should be revisited",
            contrastRatio(Color.White, bottom) < 4.5,
        )
    }

    @Test
    fun `solved scrim anchors clear AA over a worst-case white wallpaper`() {
        val (top, bottom) = ColorCascade.lightBackgroundAnchors(classicBlueWave)

        val solvedTop = solveScrimColor(argb(top), alpha = 0.72f)
        val solvedBottom = solveScrimColor(argb(bottom), alpha = 0.90f)

        val topBg = composite(solvedTop.copy(alpha = 0.72f), Color.White)
        val bottomBg = composite(solvedBottom.copy(alpha = 0.90f), Color.White)

        assertTrue(contrastRatio(Color.White, topBg) >= 4.5)
        assertTrue(contrastRatio(Color.White, bottomBg) >= 4.5)
    }

    @Test
    fun `the scrim solve keeps the theme hue instead of collapsing to neutral`() {
        val bottom = argb(ColorCascade.lightBackgroundAnchors(classicBlueWave).second)
        val solved = solveScrimColor(bottom, alpha = 0.90f)
        // Blue still dominates: darkening is a lerp toward black, which scales channels together.
        assertTrue(solved.blue > solved.green)
        assertTrue(solved.green > solved.red)
        assertTrue("should not have bottomed out at black", solved.blue > 0.15f)
    }

    @Test
    fun `an already dark anchor is returned untouched`() {
        val deep = Color(0xFF0A0F1A)
        assertEquals(deep, solveScrimColor(deep, alpha = 0.90f))
    }

    // ── Compositing / scrim alpha ─────────────────────────────────────────

    @Test
    fun `composite blends in sRGB channel space`() {
        // Tolerance is 8-bit, not float: Color.copy(alpha = 0.5f) stores 127/255 = 0.498.
        val half = composite(Color.Black.copy(alpha = 0.5f), Color.White)
        assertEquals(0.5f, half.red, 1f / 255f)
        assertEquals(0.5f, half.green, 1f / 255f)
        assertEquals(0.5f, half.blue, 1f / 255f)
    }

    @Test
    fun `a full-screen scrim over the measured bright band buries the wallpaper`() {
        // The measured failure band: backdrop relative luminance 0.539, white text at 1.89:1.
        val band = greyAtLuminance(0.539)
        assertTrue(contrastRatio(Color.White, band) < 2.0)

        val alpha = scrimAlphaFor(Color.Black, band, Color.White)
        // This is the number that rules a uniform scrim out. Nearly half the wallpaper's light is
        // thrown away across the WHOLE frame to rescue the one band that needed it, which is why
        // the instrument is a text-shaped plate at the same local alpha instead.
        assertTrue("expected ~0.40, was $alpha", alpha in 0.35f..0.45f)
    }

    @Test
    fun `no scrim is needed where the backdrop is already dark`() {
        assertEquals(0f, scrimAlphaFor(Color.Black, Color(0xFF101010), Color.White), 0f)
    }

    // ── The lightness clamp ───────────────────────────────────────────────

    @Test
    fun `the clamp preserves hue and saturation`() {
        val backgrounds = listOf(Color.White, Color.Black, greyAtLuminance(0.30), Color(0xFF128BC9))
        val foregrounds = listOf(
            Color(0xFF4A90D9), Color(0xFFFF6B6B), Color(0xFF3DDC84),
            Color(0xFFFFC107), Color(0xFF9C27B0), Color(0xFF00BCD4),
        )
        for (bg in backgrounds) for (fg in foregrounds) {
            val out = clampLightnessForContrast(fg, bg, maxShift = 1f)
            if (!out.adjusted) continue
            val (h0, s0, _) = fg.toHsl()
            val (h1, s1, _) = out.color.toHsl()
            // 1 degree, not 0.5: hue is recovered from channel *differences*, so the 1/255 floor
            // on the input colour is worth up to ~0.5 degrees on its own before the clamp does
            // anything. Anything larger than this would be a real hue shift.
            assertEquals("hue drifted for $fg on $bg", h0, h1, 1f)
            // HSL saturation is ill-conditioned near the poles — its denominator is
            // (2 - max - min), which shrinks toward zero as lightness approaches 0 or 1, so an
            // 8-bit channel wobble shows up magnified here. 0.02 is that noise floor, not slack.
            assertEquals("saturation drifted for $fg on $bg", s0, s1, 0.02f)
        }
    }

    @Test
    fun `the clamp never reports a passing colour that does not pass`() {
        val backgrounds = (0..10).map { greyAtLuminance(it / 10.0) }
        val foregrounds = listOf(Color(0xFF4A90D9), Color(0xFFFFC107), Color(0xFF9C27B0))
        for (bg in backgrounds) for (fg in foregrounds) {
            val out = clampLightnessForContrast(fg, bg, maxShift = 1f)
            if (out.meetsTarget) {
                assertTrue(
                    "claimed to pass at ${out.achievedRatio} on $bg",
                    out.achievedRatio >= 4.5f - 0.01f,
                )
            }
        }
    }

    @Test
    fun `an unadjusted result is the requested colour`() {
        val out = clampLightnessForContrast(Color.White, Color.Black)
        assertFalse(out.adjusted)
        assertEquals(Color.White, out.color)
        assertTrue(out.meetsTarget)
    }

    @Test
    fun `the clamp picks the nearer direction rather than the darker one`() {
        // A mid-tone accent on a dim backdrop: darkening cannot reach 4.5 at all here, so the
        // only solution is upward — and the search has to find it rather than giving up.
        val out = clampLightnessForContrast(Color(0xFF4A90D9), greyAtLuminance(0.10), maxShift = 1f)
        val (_, _, l0) = Color(0xFF4A90D9).toHsl()
        val (_, _, l1) = out.color.toHsl()
        assertTrue("expected a lift, got $l1 from $l0", l1 > l0)
    }

    @Test
    fun `the accent on the measured band is rescued by the clamp, not by a plate`() {
        // PfpPalette.Accent has luminance 0.264 — its ceiling is 3.34:1 on white and 6.28:1 on
        // black, so as a fill on this band it measured 1.05-1.84:1. It is nonetheless reachable:
        // a ~0.26 drop in lightness at the same hue and saturation clears AA, which is exactly
        // what step 4 of AUTO resolution is for. The plate is NOT the answer here.
        val accent = Color(0xFF4A90D9)
        val band = greyAtLuminance(0.539)
        assertTrue(contrastRatio(accent, band) < 2.0)

        val out = clampLightnessForContrast(accent, band)
        assertTrue("this case is reachable and must be reached", out.meetsTarget)
        assertTrue("reaching it changes the colour, so the notice must fire", out.adjusted)
        assertTrue(out.achievedRatio >= 4.5f)

        val (h0, s0, l0) = accent.toHsl()
        val (h1, s1, l1) = out.color.toHsl()
        assertEquals(h0, h1, 0.5f)
        assertEquals(s0, s1, 0.02f)
        assertTrue("should have darkened, not brightened", l1 < l0)
    }

    @Test
    fun `a colour too far from any passing lightness escalates instead of being overridden`() {
        // A pale yellow on a bright band. Getting it to AA means dragging it down past a dark
        // olive — at that point it is no longer the colour anyone picked, so the clamp declines,
        // hands back the request untouched and reports the miss for the caller to plate.
        val pale = Color(0xFFFFF3B0)
        val band = greyAtLuminance(0.539)

        val out = clampLightnessForContrast(pale, band)
        assertFalse("must not silently repaint the user's choice", out.adjusted)
        assertFalse(out.meetsTarget)
        assertEquals(pale, out.color)

        // …and the move it refused really is that large.
        val (_, _, l0) = pale.toHsl()
        val (_, _, l1) = clampLightnessForContrast(pale, band, maxShift = 1f).color.toHsl()
        assertTrue("the unbounded move is what maxShift exists to refuse", abs(l1 - l0) > 0.35f)
    }

    // ── HSL round trip ────────────────────────────────────────────────────

    @Test
    fun `hsl round trips`() {
        val colors = listOf(
            Color(0xFF4A90D9), Color(0xFFFF6B6B), Color(0xFF3DDC84),
            Color.White, Color.Black, Color(0xFF808080), Color(0xFFFFC107),
        )
        for (c in colors) {
            val (h, s, l) = c.toHsl()
            val back = hslToColor(h, s, l)
            assertEquals(c.red, back.red, 0.002f)
            assertEquals(c.green, back.green, 0.002f)
            assertEquals(c.blue, back.blue, 0.002f)
        }
    }

    // ── Roles ─────────────────────────────────────────────────────────────

    @Test
    fun `body text is held to AA and only large text drops to three`() {
        assertEquals(4.5f, TextContrastRole.BODY.threshold, 0f)
        assertEquals(3.0f, TextContrastRole.LARGE.threshold, 0f)
    }

    @Test
    fun `bestPolarity flips at the crossover`() {
        assertEquals(Color.White, bestPolarity(Color(0xFF101010)))
        assertEquals(Color.Black, bestPolarity(Color(0xFFF0F0F0)))
    }
}
