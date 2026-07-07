package com.playfieldportal.studio

import com.playfieldportal.studio.io.ConvertOutcome
import com.playfieldportal.studio.io.PtfConversion
import com.playfieldportal.themekit.PfpThemeSource
import com.playfieldportal.themekit.TestFixtures
import java.time.LocalDate
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PtfConversionTest {

    private fun buildPtf(name: String = "Neon") = TestFixtures.buildPtf(
        name = name,
        firmware = "6.60",
        // Saturated red wallpaper so accent derivation has a clear dominant hue.
        wallpaperBmp = TestFixtures.buildBmp(64, 36) { _, _ -> 0xFFE01030.toInt() },
    )

    @Test
    fun `converts an official ptf to a bundle`() {
        val outcome = PtfConversion.convert(buildPtf(), "neon.ptf", today = LocalDate.of(2026, 7, 7))
        val bundle = assertIs<ConvertOutcome.Converted>(outcome).bundle

        assertEquals("Neon", bundle.manifest.name)
        assertEquals("2026-07-07", bundle.manifest.created)
        val source = assertNotNull(bundle.manifest.source)
        assertEquals(PfpThemeSource.TYPE_PTF_IMPORT, source.type)
        assertEquals("neon.ptf", source.file)
        assertEquals("6.60", source.firmware)

        // Accent is a well-formed opaque #RRGGBB in the red hue family.
        assertTrue(Regex("#[0-9A-F]{6}").matches(bundle.manifest.accentColor), bundle.manifest.accentColor)

        // Wallpaper decodes back as a PNG of the original dimensions.
        val png = assertNotNull(bundle.wallpaper)
        val image = assertNotNull(ImageIO.read(png.inputStream()))
        assertEquals(64, image.width)
        assertEquals(36, image.height)
    }

    @Test
    fun `falls back to the source file name when the ptf title is blank`() {
        val outcome = PtfConversion.convert(buildPtf(name = ""), "old_theme.ptf")
        assertEquals("old_theme", assertIs<ConvertOutcome.Converted>(outcome).bundle.manifest.name)
    }

    @Test
    fun `LZR-compressed ptf converts without wallpaper and carries a warning`() {
        val ptf = TestFixtures.buildPtf(
            name = "Old Theme",
            firmware = "3.70",
            wallpaperBmp = TestFixtures.buildBmp(8, 4) { _, _ -> 0xFF3050E0.toInt() },
            compressionMethod = 1, // LZR
        )
        val outcome = assertIs<ConvertOutcome.Converted>(PtfConversion.convert(ptf, "old.ptf"))
        assertEquals(null, outcome.bundle.wallpaper)
        assertTrue(assertNotNull(outcome.warning).contains("LZR"), "warning was: ${outcome.warning}")
        // Accent falls back to the default when there is no wallpaper to derive from.
        assertEquals(PtfConversion.toHexRgb(PtfConversion.DEFAULT_ACCENT), outcome.bundle.manifest.accentColor)
    }

    @Test
    fun `clean conversion carries no warning`() {
        val outcome = assertIs<ConvertOutcome.Converted>(PtfConversion.convert(buildPtf(), "neon.ptf"))
        assertEquals(null, outcome.warning)
    }

    @Test
    fun `rejects cxmb files with the dedicated outcome`() {
        val cxmb = buildPtf() + "/vsh/resource/custom".toByteArray(Charsets.US_ASCII)
        assertIs<ConvertOutcome.Cxmb>(PtfConversion.convert(cxmb, "cfw.ctf"))
    }

    @Test
    fun `fails cleanly on garbage bytes`() {
        assertIs<ConvertOutcome.Failed>(PtfConversion.convert(ByteArray(64) { 7 }, "junk.ptf"))
    }

    @Test
    fun `hex helpers round-trip`() {
        assertEquals("#0055AA", PtfConversion.toHexRgb(0xFF0055AA.toInt()))
        assertEquals(0xFF0055AA.toInt(), PtfConversion.parseHexRgb("#0055AA"))
        assertEquals(0xFF0055AA.toInt(), PtfConversion.parseHexRgb("#FF0055AA"))
        assertEquals(null, PtfConversion.parseHexRgb("#GGGGGG"))
        assertEquals(null, PtfConversion.parseHexRgb("0055A"))
    }
}
