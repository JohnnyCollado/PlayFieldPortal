package com.playfieldportal.themekit

import com.playfieldportal.themekit.TestFixtures.gimRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PtfIconsTest {

    private val red = 0xFFE02020.toInt()
    private val blue = 0xFF2040E0.toInt()
    private val clear = 0x00000000

    private fun solid(w: Int, h: Int, argb: Int) = TestFixtures.buildGim(w, h, swizzle = true) { _, _ -> argb }

    /** Groups 2/3/4 the way an official theme lays them out: bodies even, focus odd. */
    private fun fullTheme(method: Int = 2): ByteArray = TestFixtures.buildPtfGroups(
        name = "Full",
        firmware = "5.00",
        groups = mapOf(
            2 to (0..7).map { gimRecord(it, solid(64, 48, red), method) },
            3 to (0..9).map { gimRecord(it, solid(48, 48, if (it % 2 == 0) red else blue), method) },
            4 to (0..3).map { gimRecord(it, solid(32, 32, if (it % 2 == 0) red else blue), method) },
        ),
    )

    private fun extract(ptf: ByteArray) = PtfIcons.extract(assertNotNull(PtfUnpacker.unpack(ptf)))

    @Test
    fun `direct map covers exactly the agreed slots, all valid keys`() {
        val keys = PtfIcons.DIRECT.values.flatten()
        keys.forEach { assertTrue(IconSlots.isValidKey(it), "$it must be a registered icon slot") }
        assertEquals(
            setOf(
                "catbar_settings", "catbar_photos", "catbar_music", "catbar_video", "catbar_games",
                "catbar_network", "item_memcard_games", "item_memcard_music", "item_memcard_video",
                "item_memcard_photos", "item_umd", "item_camera", "item_settings",
            ),
            keys.toSet(),
        )
        assertEquals(keys.size, keys.toSet().size, "no key is fed by two PTF slots")
    }

    @Test
    fun `a full theme fills every direct key`() {
        assertEquals(PtfIcons.DIRECT.values.flatten().toSet(), extract(fullTheme()).keys)
    }

    @Test
    fun `item icons come from the body record, never the focus record`() {
        val icons = extract(fullTheme())
        assertEquals(red, assertNotNull(icons["item_umd"])[24, 24])
        assertEquals(red, assertNotNull(icons["item_settings"])[16, 16])
    }

    @Test
    fun `memory stick fans out to all four memory-card keys sharing one image`() {
        val icons = extract(fullTheme())
        val source = assertNotNull(icons["item_memcard_games"])
        listOf("item_memcard_music", "item_memcard_video", "item_memcard_photos")
            .forEach { assertSame(source, icons[it]) }
        assertEquals(10, PtfIcons.tintSources(icons).size, "fan-out must not add weight to the tint")
    }

    @Test
    fun `a 64x48 category is padded to a centred 64x64 square`() {
        val icon = assertNotNull(extract(fullTheme())["catbar_music"])
        assertEquals(64, icon.width); assertEquals(64, icon.height)
        assertEquals(clear, icon[32, 7]); assertEquals(red, icon[32, 8])
        assertEquals(red, icon[32, 55]); assertEquals(clear, icon[32, 56])
    }

    @Test
    fun `a narrow item is padded sideways without stretching`() {
        val ptf = TestFixtures.buildPtfGroups(
            "Narrow", "6.20",
            mapOf(3 to listOf(gimRecord(4, solid(42, 48, red)))),
        )
        val icon = assertNotNull(extract(ptf)["item_umd"])
        assertEquals(48, icon.width); assertEquals(48, icon.height)
        assertEquals(clear, icon[2, 24]); assertEquals(red, icon[3, 24])
        assertEquals(red, icon[44, 24]); assertEquals(clear, icon[45, 24])
    }

    @Test
    fun `a categories-only theme yields just the six category keys`() {
        val ptf = TestFixtures.buildPtfGroups(
            "Cats", "3.70",
            mapOf(2 to (1..7).map { gimRecord(it, solid(48, 48, red)) }),
        )
        assertEquals(
            setOf("catbar_settings", "catbar_photos", "catbar_music", "catbar_video", "catbar_games", "catbar_network"),
            extract(ptf).keys,
        )
    }

    @Test
    fun `a record that is not an image is skipped and the rest still extract`() {
        val ptf = TestFixtures.buildPtfGroups(
            "Broken", "5.00",
            mapOf(3 to listOf(
                TestFixtures.opaqueRecord(2, ByteArray(64) { 7 }),
                gimRecord(4, solid(48, 48, red)),
            )),
        )
        assertEquals(setOf("item_umd"), extract(ptf).keys)
    }

    @Test
    fun `LZR records extract the same as zlib`() {
        assertEquals(extract(fullTheme(method = 2)).keys, extract(fullTheme(method = 1)).keys)
    }

    @Test
    fun `wallpaper-only themes yield no icons`() {
        val bmp = TestFixtures.buildBmp(8, 4) { _, _ -> red }
        assertTrue(extract(TestFixtures.buildPtf("Wp", "5.00", bmp)).isEmpty())
    }
}
