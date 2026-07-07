package com.playfieldportal.themekit

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PtfParserTest {

    private val pink = 0xFFFF72B1.toInt()

    private fun syntheticPtf(name: String = "Test Theme", fw: String = "6.20"): ByteArray {
        val bmp = TestFixtures.buildBmp(8, 4) { x, y -> if ((x + y) % 2 == 0) pink else 0xFF102030.toInt() }
        return TestFixtures.buildPtf(name, fw, bmp)
    }

    @Test
    fun `parses header name and firmware`() {
        val theme = assertNotNull(PtfParser.parse(syntheticPtf()))
        assertEquals("Test Theme", theme.name)
        assertEquals("6.20", theme.firmware)
    }

    @Test
    fun `name longer than the 16-byte field is truncated, matching the format`() {
        val theme = assertNotNull(PtfParser.parse(syntheticPtf(name = "Evangelion 2.0: You Can (Not) Advance")))
        assertEquals("Evangelion 2.0:", theme.name)
    }

    @Test
    fun `reads the slot table`() {
        val theme = assertNotNull(PtfParser.parse(syntheticPtf()))
        assertEquals(1, theme.slots.size)
        val slot = theme.slots.single()
        assertEquals(1, slot.id)
        assertEquals(0x140, slot.dataOffset)
    }

    @Test
    fun `extracts and decodes the wallpaper through zlib and BMP`() {
        val theme = assertNotNull(PtfParser.parse(syntheticPtf()))
        val wallpaper = assertNotNull(theme.wallpaper)
        assertEquals(8, wallpaper.width)
        assertEquals(4, wallpaper.height)
        assertEquals(pink, wallpaper[0, 0])           // checkerboard corner
        assertEquals(0xFF102030.toInt(), wallpaper[1, 0])
        assertEquals(pink, wallpaper[7, 3])           // bottom-right: bottom-up rows decoded correctly
    }

    @Test
    fun `detect distinguishes official, cxmb, and garbage`() {
        assertEquals(PtfParser.Kind.OFFICIAL_PTF, PtfParser.detect(syntheticPtf()))

        val cxmb = syntheticPtf() + "/vsh/resource/topmenu_plugin.rco".toByteArray()
        assertEquals(PtfParser.Kind.CXMB, PtfParser.detect(cxmb))

        assertEquals(PtfParser.Kind.NOT_PTF, PtfParser.detect(ByteArray(0x200)))
        assertEquals(PtfParser.Kind.NOT_PTF, PtfParser.detect("not a theme".toByteArray()))
    }

    @Test
    fun `parse rejects cxmb and garbage`() {
        assertNull(PtfParser.parse(syntheticPtf() + "/vsh/resource/paf.prx".toByteArray()))
        assertNull(PtfParser.parse(ByteArray(0x200)))
    }

    @Test
    fun `truncated wallpaper stream yields theme without wallpaper, not a crash`() {
        val full = syntheticPtf()
        val truncated = full.copyOf(full.size - 16) // cut into the zlib stream
        val theme = assertNotNull(PtfParser.parse(truncated))
        assertNull(theme.wallpaper)
        assertEquals(PtfParser.WallpaperStatus.CORRUPT, theme.wallpaperStatus)
    }

    @Test
    fun `zlib wallpaper reports DECODED status`() {
        val theme = assertNotNull(PtfParser.parse(syntheticPtf()))
        assertEquals(PtfParser.WallpaperStatus.DECODED, theme.wallpaperStatus)
    }

    @Test
    fun `LZR wallpaper (fw 3_70 era) decodes through the LZR path`() {
        val bmp = TestFixtures.buildBmp(8, 4) { x, y -> if ((x + y) % 2 == 0) pink else 0xFF102030.toInt() }
        val ptf = TestFixtures.buildPtf("Old Theme", "3.70", bmp, compressionMethod = 1)
        val theme = assertNotNull(PtfParser.parse(ptf))
        val wallpaper = assertNotNull(theme.wallpaper)
        assertEquals(PtfParser.WallpaperStatus.DECODED, theme.wallpaperStatus)
        assertEquals(pink, wallpaper[0, 0])
        assertEquals(0xFF102030.toInt(), wallpaper[1, 0])
    }

    @Test
    fun `damaged LZR wallpaper reports CORRUPT`() {
        val bmp = TestFixtures.buildBmp(8, 4) { _, _ -> pink }
        val ptf = TestFixtures.buildPtf("Old Theme", "3.70", bmp, compressionMethod = 1)
        // Trash the LZR stream body (past the 32-byte payload header + 5-byte LZR header).
        for (i in 0x140 + 37 until ptf.size) ptf[i] = 0x5A
        val theme = assertNotNull(PtfParser.parse(ptf))
        assertNull(theme.wallpaper)
        assertEquals(PtfParser.WallpaperStatus.CORRUPT, theme.wallpaperStatus)
    }

    @Test
    fun `unknown compression method reports UNSUPPORTED_COMPRESSION`() {
        val bmp = TestFixtures.buildBmp(8, 4) { _, _ -> pink }
        val ptf = TestFixtures.buildPtf("Weird", "9.99", bmp, compressionMethod = 3)
        val theme = assertNotNull(PtfParser.parse(ptf))
        assertNull(theme.wallpaper)
        assertEquals(PtfParser.WallpaperStatus.UNSUPPORTED_COMPRESSION, theme.wallpaperStatus)
    }

    @Test
    fun `theme without a wallpaper slot reports MISSING`() {
        val full = syntheticPtf()
        full[0x120] = 9 // rewrite the slot descriptor's id (u16 at 0x120) to a non-wallpaper id
        val theme = assertNotNull(PtfParser.parse(full))
        assertNull(theme.wallpaper)
        assertEquals(PtfParser.WallpaperStatus.MISSING, theme.wallpaperStatus)
    }

    @Test
    fun `payload header with a lying compressed size still decodes via the scan fallback`() {
        val full = syntheticPtf()
        val dataOffset = 0x140
        // Claim a compressed size far past the slot: header is implausible, scan takes over.
        full[dataOffset + 8] = 0xFF.toByte()
        full[dataOffset + 9] = 0xFF.toByte()
        full[dataOffset + 10] = 0x7F.toByte()
        val theme = assertNotNull(PtfParser.parse(full))
        assertNotNull(theme.wallpaper)
        assertEquals(PtfParser.WallpaperStatus.DECODED, theme.wallpaperStatus)
    }
}
