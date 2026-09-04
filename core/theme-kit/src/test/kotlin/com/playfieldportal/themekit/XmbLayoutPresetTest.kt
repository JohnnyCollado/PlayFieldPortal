package com.playfieldportal.themekit

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the whole PSP auto-fit derivation (docs/xmb-layout-proportional-formula.md):
 * feeding the AYN Thor's exact window (1920 x 1080 @ 369 dpi) must reproduce the
 * hand-tuned state it was derived from — scale ~= 1.32, barTopFraction ~= 0.13,
 * barLeftFraction ~= -0.05 — and land on the 630.3 x 354.5 dp reference canvas.
 */
class XmbLayoutPresetTest {

    private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.002f) {
        assertTrue(
            abs(expected - actual) <= tolerance,
            "expected $actual to be within $tolerance of $expected",
        )
    }

    @Test
    fun `Thor reference window reproduces the hand-tuned compact values`() {
        val raw = XmbLayoutPreset.computeRawForWindow(
            widthPx = 1920f, heightPx = 1080f, densityDpi = 369f,
        )

        assertClose(1.32f, raw.scale)
        assertClose(0.13f, raw.barTopFraction)
        assertClose(-0.05f, raw.barLeftFraction)

        // The tuned reference canvas: 630.3 x 354.5 dp (16:9), height driven exactly.
        assertClose(354.55f, raw.canvasH, tolerance = 0.05f)
        assertClose(630.30f, raw.canvasW, tolerance = 0.05f)
    }

    @Test
    fun `Thor values survive the codec sanitize gate unchanged`() {
        val preset = XmbLayoutPreset.computeForWindow(
            widthPx = 1920f, heightPx = 1080f, densityDpi = 369f,
        )
        val sanitized = XmbLayoutAdjustCodec.sanitize(preset)

        assertEquals(preset, sanitized)
    }

    @Test
    fun `any 16x9 panel lands on the identical reference canvas`() {
        // Odin 2 — 1920x1080 @ 480 dpi. Different density, same physical aspect: the
        // formula must cancel density out and produce the same canvas + fractions.
        val odin = XmbLayoutPreset.computeRawForWindow(
            widthPx = 1920f, heightPx = 1080f, densityDpi = 480f,
        )

        assertClose(354.55f, odin.canvasH, tolerance = 0.05f)
        assertClose(630.30f, odin.canvasW, tolerance = 0.05f)
        assertClose(0.13f, odin.barTopFraction)
        assertClose(-0.05f, odin.barLeftFraction)
    }

    @Test
    fun `taller-aspect phone keeps the canvas height and widens the canvas`() {
        // 2400x1080 @ 440 dpi — extra width becomes label room; the cross stays
        // left-anchored, so barLeftFraction moves toward zero as the canvas widens.
        val phone = XmbLayoutPreset.computeRawForWindow(
            widthPx = 2400f, heightPx = 1080f, densityDpi = 440f,
        )

        assertClose(354.55f, phone.canvasH, tolerance = 0.05f)
        assertClose(787.85f, phone.canvasW, tolerance = 0.5f)
        assertClose(-0.04f, phone.barLeftFraction)
    }

    @Test
    fun `narrower 16x10 tablet drifts barLeftFraction more negative`() {
        // 2560x1600 @ 320 dpi — width-bound uiScale; the fixed 105.49 dp anchor occupies
        // a larger fraction of the smaller canvas, so the fraction goes below -0.05.
        val tablet = XmbLayoutPreset.computeRawForWindow(
            widthPx = 2560f, heightPx = 1600f, densityDpi = 320f,
        )

        assertClose(354.55f, tablet.canvasH, tolerance = 0.05f)
        assertClose(567.2f, tablet.canvasW, tolerance = 0.5f)
        assertClose(-0.0556f, tablet.barLeftFraction)
    }

    @Test
    fun `near-square foldable clamps at SCALE_MAX and degrades gracefully`() {
        // Z Fold inner 2176x1812 @ 373 dpi — uiScale is width-bound, so the wanted scale
        // (1.954) exceeds SCALE_MAX and clips to 1.8; step 2's fallback then keeps the
        // caticon band on the correct screen line instead of breaking the layout.
        val fold = XmbLayoutPreset.computeRawForWindow(
            widthPx = 2176f, heightPx = 1812f, densityDpi = 373f,
        )

        assertEquals(XmbLayoutAdjust.SCALE_MAX, fold.scale)
        assertClose(0.1469f, fold.barTopFraction)
        assertClose(-0.0682f, fold.barLeftFraction)
    }

    @Test
    fun `dp entry point matches the px entry point for the Thor window`() {
        // displayMetrics.density for the Thor is 369/160; 832.52 x 468.29 dp is its
        // landscape window. Both entry points must agree.
        val fromPx = XmbLayoutPreset.computeForWindow(
            widthPx = 1920f, heightPx = 1080f, densityDpi = 369f,
        )
        val fromDp = XmbLayoutPreset.computeForWindowDp(
            widthDp = 832.5f, heightDp = 468.3f, density = 369f / 160f,
        )

        assertClose(fromPx.scale, fromDp.scale)
        assertClose(fromPx.barLeftFraction, fromDp.barLeftFraction)
        assertClose(fromPx.barTopFraction, fromDp.barTopFraction)
    }

    @Test
    fun `computeForWindowDp normalizes portrait input to landscape`() {
        // PFP is landscape-fixed; a transient portrait Configuration must still produce
        // the same preset as its landscape equivalent.
        val landscape = XmbLayoutPreset.computeForWindowDp(832.5f, 468.3f, 369f / 160f)
        val portrait = XmbLayoutPreset.computeForWindowDp(468.3f, 832.5f, 369f / 160f)

        assertEquals(landscape, portrait)
    }
}
