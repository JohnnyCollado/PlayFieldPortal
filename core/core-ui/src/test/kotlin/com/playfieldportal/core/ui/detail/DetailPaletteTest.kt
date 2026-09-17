package com.playfieldportal.core.ui.detail

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.playfieldportal.core.domain.model.lightBackgroundAnchors
import com.playfieldportal.core.ui.theme.PFPColors
import com.playfieldportal.core.ui.theme.composite
import com.playfieldportal.core.ui.theme.contrastRatio
import com.playfieldportal.core.ui.theme.relativeLuminance
import com.playfieldportal.core.ui.theme.storefrontColorsFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the detail page's shading to the App Drawer: the same translucent theme gradient (so the XMB
 * wave reads through identically), the mockup's layering on top of it (rows a step darker than the
 * page, a lifted edge, one bright accent for focus), and a hero that never pushes the primary
 * actions under the footer. Pure color math and arithmetic — no Compose runtime.
 */
class DetailPaletteTest {

    /** The palette XmbColorScheme.resolve produces for a wave color (white accent). */
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

    private val classicBlue = scheme(0xFF0055AA)

    /** One wave per scheme family, including the pale ones that flip the drawer to dark text. */
    private val schemes = listOf(
        classicBlue,
        scheme(0xFFFF7A1A), // sunset orange
        scheme(0xFF2FA84F), // fresh green
        scheme(0xFF6A3FB5), // royal purple
        scheme(0xFFC0182B), // crimson red
        scheme(0xFFB8C4D0), // silver
        scheme(0xFFF29BB8), // sakura pink
        scheme(0xFFE0A21A), // golden amber
        scheme(0xFF18A7A0), // aqua teal
        scheme(0xFF14204A), // midnight navy
        scheme(0xFF2B2B2B), // charcoal
    )

    // ── Matches the App Drawer ────────────────────────────────────────────

    @Test
    fun `the page is the App Drawer's gradient at the App Drawer's alpha`() {
        for (pfp in schemes) {
            val drawer = storefrontColorsFor(pfp)
            val page = detailPaletteFor(pfp)
            assertEquals(drawer.backgroundDeep, page.pageTop)
            assertEquals(drawer.backgroundMid, page.pageBottom)
        }
        // 0.88 stored in 8 bits reads back as 0.878.
        assertEquals(0.88f, detailPaletteFor(classicBlue).pageTop.alpha, 0.005f)
    }

    @Test
    fun `header and footer bands are see-through like the App Drawer's`() {
        val p = detailPaletteFor(classicBlue)
        assertEquals(0f, p.header.alpha, 0f)
        assertEquals(0f, p.footer.alpha, 0f)
    }

    @Test
    fun `text, focus and divider use the App Drawer's roles`() {
        for (pfp in schemes) {
            val drawer = storefrontColorsFor(pfp)
            val p = detailPaletteFor(pfp)
            assertEquals(drawer.textPrimary, p.textPrimary)
            assertEquals(drawer.tileSelectedEdge, p.focus)
            assertEquals(drawer.chromeDivider, p.divider)
        }
        // Muted text is the drawer's own wherever that reads on a row (see the readability test).
        assertEquals(storefrontColorsFor(classicBlue).textSecondary, detailPaletteFor(classicBlue).textMuted)
    }

    // ── The mockup's layering, over the lighter page ──────────────────────

    /** What a surface actually looks like on screen: over the page, over the brightest wave. */
    private fun onScreen(surface: Color, page: Color): Color = composite(surface, composite(page, Color.White))

    @Test
    fun `classic blue is no darker than the App Drawer`() {
        val p = detailPaletteFor(classicBlue)
        val drawerPage = composite(storefrontColorsFor(classicBlue).backgroundDeep, Color.Black)
        assertTrue(
            "the page must not be darkened past the drawer",
            relativeLuminance(composite(p.pageTop, Color.Black)) >= relativeLuminance(drawerPage) - 0.0001,
        )
    }

    @Test
    fun `rows sit a step darker than the page on dark schemes`() {
        val p = detailPaletteFor(classicBlue)
        val page = composite(p.pageTop, Color.Black)
        val row = composite(p.rowFill, page)
        assertTrue(relativeLuminance(row) < relativeLuminance(page))
        assertTrue("rows stay translucent so the wave reads through", p.rowFill.alpha < 1f)
    }

    @Test
    fun `text stays readable on rows for every scheme`() {
        for (pfp in schemes) {
            val p = detailPaletteFor(pfp)
            val row = onScreen(p.rowFill, p.pageTop)
            assertTrue("$pfp: primary text on a row", contrastRatio(p.textPrimary, row) >= 4.5)
            assertTrue("$pfp: muted text on a row", contrastRatio(p.textMuted, row) >= 3.0)
        }
    }

    // ── Hero sizing ───────────────────────────────────────────────────────

    @Test
    fun `the hero keeps its full height when everything fits`() {
        assertEquals(DetailHeroHeight, detailHeroHeightFor(viewport = 600.dp))
    }

    @Test
    fun `the hero gives up height so the primary actions clear the footer`() {
        // The AYN Thor: 468dp tall, less the breadcrumb and the footer.
        val viewport = 468.dp - 64.dp - DetailFooterHeight
        val hero = detailHeroHeightFor(viewport)
        assertTrue("hero $hero must shrink below $DetailHeroHeight", hero < DetailHeroHeight)
        assertTrue("the band below the hero must fit", hero + DetailHeroBandBelow <= viewport)
    }

    @Test
    fun `a message line under the actions takes its room from the hero too`() {
        val viewport = 468.dp - 64.dp - DetailFooterHeight
        val withMessage = detailHeroHeightFor(viewport, messageLine = true)
        assertTrue(withMessage < detailHeroHeightFor(viewport))
        assertTrue(
            "the band and its message line must fit",
            withMessage + DetailHeroBandBelow + DetailActionMessageHeight <= viewport,
        )
    }

    @Test
    fun `the hero never collapses below its minimum`() {
        assertEquals(DetailHeroMinHeight, detailHeroHeightFor(viewport = 120.dp))
    }

    @Test
    fun `the hero aspect is the banner's full-size width over its full height`() {
        assertEquals(DetailContentMaxWidth - DetailContentPadding * 2, DetailHeroWidth)
        assertEquals(DetailHeroWidth.value / DetailHeroHeight.value, DetailHeroAspect, 0.0001f)
    }
}
