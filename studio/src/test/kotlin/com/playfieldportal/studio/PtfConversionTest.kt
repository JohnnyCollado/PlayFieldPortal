package com.playfieldportal.studio

import com.playfieldportal.studio.io.ConvertOutcome
import com.playfieldportal.studio.io.PtfConversion
import com.playfieldportal.themekit.PfpThemeManifest
import com.playfieldportal.themekit.PfpThemeSource
import com.playfieldportal.themekit.PtfIcons
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

    private val orange = 0xFFE07020.toInt()

    /** A theme carrying solid orange icons in groups 2, 3 and 4 over the red wallpaper. */
    private fun iconPtf(): ByteArray {
        fun icon(w: Int, h: Int) = TestFixtures.buildGim(w, h, swizzle = true) { _, _ -> orange }
        return TestFixtures.buildPtfGroups(
            name = "Icons",
            firmware = "6.20",
            groups = mapOf(
                2 to (0..7).map { TestFixtures.gimRecord(it, icon(64, 48)) },
                3 to (0..9).map { TestFixtures.gimRecord(it, icon(48, 48)) },
                4 to (0..3).map { TestFixtures.gimRecord(it, icon(32, 32)) },
            ),
            wallpaperBmp = TestFixtures.buildBmp(64, 36) { _, _ -> 0xFFE01030.toInt() },
        )
    }

    @Test
    fun `carries the direct-fit icons as square PNGs`() {
        val bundle = assertIs<ConvertOutcome.Converted>(PtfConversion.convert(iconPtf(), "icons.ptf")).bundle
        assertEquals(PtfIcons.DIRECT.values.flatten().toSet(), bundle.icons.keys)
        val music = assertNotNull(bundle.icons["catbar_music"])
        assertEquals("png", music.extension)
        val image = assertNotNull(ImageIO.read(music.bytes.inputStream()))
        assertEquals(64, image.width)
        assertEquals(64, image.height)
        assertEquals(0, image.getRGB(32, 0) ushr 24, "the padding is transparent")
    }

    @Test
    fun `keeps the other body images as square PNG extras and leaves out the focus variants`() {
        val bundle = assertIs<ConvertOutcome.Converted>(PtfConversion.convert(iconPtf(), "icons.ptf")).bundle
        val tv = assertNotNull(bundle.ptfIcons[PtfIcons.SlotRef(2, 5)])
        assertEquals("png", tv.extension)
        val image = assertNotNull(ImageIO.read(tv.bytes.inputStream()))
        assertEquals(64, image.width)
        assertEquals(64, image.height)
        assertTrue(PtfIcons.SlotRef(3, 9) !in bundle.ptfIcons, "an odd item index is a focus variant")
    }

    @Test
    fun `a strong vivid icon tint becomes the icon colour and the accent`() {
        val manifest = assertIs<ConvertOutcome.Converted>(PtfConversion.convert(iconPtf(), "icons.ptf")).bundle.manifest
        assertEquals("#E07020", manifest.iconColor)
        assertEquals("#E07020", manifest.accentColor)
    }

    @Test
    fun `a wallpaper-only ptf carries no icons and keeps the automatic icon colour`() {
        val bundle = assertIs<ConvertOutcome.Converted>(PtfConversion.convert(buildPtf(), "neon.ptf")).bundle
        assertTrue(bundle.icons.isEmpty())
        assertEquals(PfpThemeManifest.ICON_COLOR_AUTO, bundle.manifest.iconColor)
    }

    @Test
    fun `falls back to the source file name when the ptf title is blank`() {
        val outcome = PtfConversion.convert(buildPtf(name = ""), "old_theme.ptf")
        assertEquals("old_theme", assertIs<ConvertOutcome.Converted>(outcome).bundle.manifest.name)
    }

    @Test
    fun `LZR ptf (fw 3_70) converts with its wallpaper and no warning`() {
        val ptf = TestFixtures.buildPtf(
            name = "Old Theme",
            firmware = "3.70",
            wallpaperBmp = TestFixtures.buildBmp(8, 4) { _, _ -> 0xFF3050E0.toInt() },
            compressionMethod = 1, // LZR
        )
        val outcome = assertIs<ConvertOutcome.Converted>(PtfConversion.convert(ptf, "old.ptf"))
        assertNotNull(outcome.bundle.wallpaper)
        assertEquals(null, outcome.warning)
    }

    @Test
    fun `damaged wallpaper converts without it and carries a warning`() {
        val ptf = TestFixtures.buildPtf(
            name = "Old Theme",
            firmware = "3.70",
            wallpaperBmp = TestFixtures.buildBmp(8, 4) { _, _ -> 0xFF3050E0.toInt() },
            compressionMethod = 1,
        )
        for (i in 0x140 + 37 until ptf.size) ptf[i] = 0x5A // trash the LZR stream body
        val outcome = assertIs<ConvertOutcome.Converted>(PtfConversion.convert(ptf, "old.ptf"))
        assertEquals(null, outcome.bundle.wallpaper)
        assertTrue(assertNotNull(outcome.warning).contains("damaged"), "warning was: ${outcome.warning}")
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
