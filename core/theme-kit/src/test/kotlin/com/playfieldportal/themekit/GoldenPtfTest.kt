package com.playfieldportal.themekit

import java.io.File
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Golden tests against real PSP theme files (Sony's official examples + a fan theme).
 * The files are NOT committed (they're third-party content); tests self-skip unless the
 * corpus is present. Point [goldenDir] elsewhere with -Dthemekit.golden.dir=... .
 */
class GoldenPtfTest {

    private val goldenDir = File(
        System.getProperty("themekit.golden.dir")
            ?: "${System.getProperty("user.home")}${File.separator}Downloads",
    )

    private fun golden(name: String): ByteArray {
        val file = File(goldenDir, name)
        assumeTrue("golden file missing: $file — skipping", file.isFile)
        return file.readBytes()
    }

    private fun hueOf(argb: Int): Float {
        val r = (argb shr 16 and 0xFF) / 255f
        val g = (argb shr 8 and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val v = maxOf(r, g, b); val d = v - minOf(r, g, b)
        if (d == 0f) return 0f
        val h = when (v) {
            r -> ((g - b) / d) % 6f
            g -> (b - r) / d + 2f
            else -> (r - g) / d + 4f
        } / 6f
        return if (h < 0) h + 1f else h
    }

    @Test
    fun `classypink - Sony official example`() {
        val theme = assertNotNull(PtfParser.parse(golden("classypink.ptf")))
        assertTrue(theme.name.startsWith("Classy Pink"), "name was '${theme.name}'")
        assertEquals("5.00", theme.firmware)
        assertEquals(5, theme.slots.size)

        val wallpaper = assertNotNull(theme.wallpaper)
        assertEquals(480, wallpaper.width)
        assertEquals(272, wallpaper.height)

        val accent = assertNotNull(AccentDeriver.deriveAccent(wallpaper))
        val hue = hueOf(accent)
        assertTrue(hue > 0.85f, "classypink accent should be pink, hue=$hue")
    }

    @Test
    fun `cookies - Sony official example`() {
        val theme = assertNotNull(PtfParser.parse(golden("cookies.ptf")))
        assertTrue(theme.name.startsWith("Cookies"), "name was '${theme.name}'")
        assertEquals(5, theme.slots.size)

        val wallpaper = assertNotNull(theme.wallpaper)
        assertEquals(480, wallpaper.width)
        assertEquals(272, wallpaper.height)

        val accent = assertNotNull(AccentDeriver.deriveAccent(wallpaper))
        val hue = hueOf(accent)
        assertTrue(hue in 0.02f..0.17f, "cookies accent should be warm amber, hue=$hue")
    }

    @Test
    fun `evangelion - fan theme, fw 6_20`() {
        val theme = assertNotNull(PtfParser.parse(golden("Evangelion 2.0 You Can (Not) Advance.ptf")))
        assertEquals("6.20", theme.firmware)

        val wallpaper = assertNotNull(theme.wallpaper)
        assertEquals(480, wallpaper.width)
        assertEquals(272, wallpaper.height)

        val accent = assertNotNull(AccentDeriver.deriveAccent(wallpaper))
        val hue = hueOf(accent)
        assertTrue(hue < 0.06f || hue > 0.94f, "evangelion accent should be NERV red, hue=$hue")
    }

    @Test
    fun `crisis core - official fw 3_70 theme, LZR-compressed wallpaper`() {
        val theme = assertNotNull(PtfParser.parse(golden("CrisisCoreFF7.ptf")))
        assertEquals("3.70", theme.firmware)
        assertEquals(PtfParser.WallpaperStatus.DECODED, theme.wallpaperStatus)

        val wallpaper = assertNotNull(theme.wallpaper)
        assertEquals(480, wallpaper.width)
        assertEquals(272, wallpaper.height)
    }

    @Test
    fun `serial experiments lain - fw 3_70 theme, LZR-compressed wallpaper`() {
        val theme = assertNotNull(PtfParser.parse(golden("SerialExperimentsLain.ptf")))
        assertEquals("3.70", theme.firmware)
        assertEquals(PtfParser.WallpaperStatus.DECODED, theme.wallpaperStatus)

        val wallpaper = assertNotNull(theme.wallpaper)
        assertEquals(480, wallpaper.width)
        assertEquals(272, wallpaper.height)
    }

    @Test
    fun `crisis core - full unpack recovers every resource`() {
        val dump = assertNotNull(PtfUnpacker.unpack(golden("CrisisCoreFF7.ptf")))
        assertEquals("CCFFVII", dump.name)

        val failed = dump.resources.filter { it.kind == PtfUnpacker.Resource.Kind.FAILED }
        assertTrue(failed.isEmpty(), "failed resources: ${failed.map { "${it.slotId}/${it.sequence}" }}")

        val images = dump.resources.mapNotNull { it.image }
        assertTrue(images.size >= 55, "expected the full icon set, got ${images.size} images")
        // The embedded 300x170 preview and the 480x272 wallpaper both decode.
        assertTrue(images.any { it.width == 300 && it.height == 170 }, "preview missing")
        assertTrue(images.any { it.width == 480 && it.height == 272 }, "wallpaper missing")
        // Focused icon variants carry transparency.
        assertTrue(
            images.any { img -> img.argb.any { p -> (p ushr 24) == 0 } },
            "no transparent pixels anywhere — alpha lost?",
        )
    }

    @Test
    fun `detect flags a CXMB ctf when present`() {
        val file = File(goldenDir, "PSP-Themes-master/Blue Flame.ctf")
        assumeTrue("no CXMB sample present — skipping", file.isFile)
        assertEquals(PtfParser.Kind.CXMB, PtfParser.detect(file.readBytes()))
    }
}
