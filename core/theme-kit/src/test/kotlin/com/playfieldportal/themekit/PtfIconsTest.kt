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
        keys.forEach { assertTrue(CustomizableIcons.isValidKey(it), "$it must be a registered icon slot") }
        assertEquals(
            setOf(
                "catbar_settings", "catbar_photos", "catbar_music", "catbar_video", "catbar_games",
                "sysicon_default", "catbar_network", "item_memcard_games", "item_memcard_music",
                "item_memcard_video", "item_memcard_photos", "sysicon_allgames", "item_shiba_track",
                "item_umd", "item_camera", "item_settings",
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
    fun `memory stick fans out to every slot drawing the memory-card art, sharing one image`() {
        val icons = extract(fullTheme())
        val source = assertNotNull(icons["item_memcard_games"])
        SharedIconArt.MEMORY_CARD.forEach { assertSame(source, icons[it], it) }
        assertSame(source, icons["sysicon_allgames"], "All Games draws the memory-card art")
        assertEquals(10, PtfIcons.tintSources(icons).size, "fan-out must not add weight to the tint")
    }

    @Test
    fun `the game category fans out to the generic console art it shares`() {
        val icons = extract(fullTheme())
        SharedIconArt.GAMES.forEach { assertSame(icons["catbar_games"], icons[it], it) }
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
    fun `a categories-only theme yields just the six category keys and what they share`() {
        val ptf = TestFixtures.buildPtfGroups(
            "Cats", "3.70",
            mapOf(2 to (1..7).map { gimRecord(it, solid(48, 48, red)) }),
        )
        assertEquals(
            setOf(
                "catbar_settings", "catbar_photos", "catbar_music", "catbar_video", "catbar_games",
                "sysicon_default", "catbar_network",
            ),
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

    private fun extras(ptf: ByteArray) = PtfIcons.extractExtras(assertNotNull(PtfUnpacker.unpack(ptf)))

    @Test
    fun `extras hold every non-direct category and the even non-direct item records`() {
        val got = extras(fullTheme()).keys
        assertEquals(
            setOf(
                PtfIcons.SlotRef(2, 0), PtfIcons.SlotRef(2, 5),
                PtfIcons.SlotRef(3, 0), PtfIcons.SlotRef(3, 8),
                PtfIcons.SlotRef(4, 0),
            ),
            got,
        )
    }

    @Test
    fun `extras never include an odd 3 or 4 record or a direct ref`() {
        val got = extras(fullTheme()).keys
        assertTrue(got.none { it.group != 2 && it.index % 2 == 1 })
        assertTrue(got.none { it in PtfIcons.DIRECT.keys })
        // Group 2 odd indices are real categories, so a non-direct odd one would be kept.
        val cats = TestFixtures.buildPtfGroups("Odd", "5.00", mapOf(2 to listOf(gimRecord(9, solid(48, 48, red)))))
        assertEquals(setOf(PtfIcons.SlotRef(2, 9)), extras(cats).keys)
    }

    @Test
    fun `extras are padded square`() {
        val tv = assertNotNull(extras(fullTheme())[PtfIcons.SlotRef(2, 5)])
        assertEquals(64, tv.width); assertEquals(64, tv.height)
        assertEquals(clear, tv[32, 7]); assertEquals(red, tv[32, 8])
    }

    @Test
    fun `an undecodable extra is skipped and the rest remain`() {
        val ptf = TestFixtures.buildPtfGroups(
            "Broken", "5.00",
            mapOf(2 to listOf(
                TestFixtures.opaqueRecord(5, ByteArray(64) { 7 }),
                gimRecord(8, solid(48, 48, red)),
            )),
        )
        assertEquals(setOf(PtfIcons.SlotRef(2, 8)), extras(ptf).keys)
    }

    @Test
    fun `groups outside 2 to 4 are never extras`() {
        val ptf = TestFixtures.buildPtfGroups(
            "Other", "5.00",
            mapOf(
                0 to listOf(gimRecord(0, solid(48, 48, red))),
                5 to listOf(gimRecord(0, solid(48, 48, red))),
                2 to listOf(gimRecord(0, solid(48, 48, red))),
            ),
        )
        assertEquals(setOf(PtfIcons.SlotRef(2, 0)), extras(ptf).keys)
    }

    @Test
    fun `more than 64 bodies keep exactly the lowest 64 refs`() {
        val ptf = TestFixtures.buildPtfGroups(
            "Big", "5.00",
            mapOf(
                2 to (0..39).map { gimRecord(it, solid(16, 16, red)) },
                3 to (0..99 step 2).map { gimRecord(it, solid(16, 16, red)) },
            ),
        )
        val got = extras(ptf).keys
        assertEquals(PtfIcons.MAX_EXTRAS, got.size)
        val all = (0..39).map { PtfIcons.SlotRef(2, it) } + (0..99 step 2).map { PtfIcons.SlotRef(3, it) }
        val want = all.filter { it !in PtfIcons.DIRECT.keys }.sortedWith(compareBy({ it.group }, { it.index })).take(64)
        assertEquals(want.toSet(), got)
        assertEquals(want, extras(ptf).keys.toList(), "iteration order is (group, index)")
    }

    @Test
    fun `isBody follows the group rules`() {
        assertTrue(PtfIcons.isBody(PtfIcons.SlotRef(2, 0)))
        assertTrue(PtfIcons.isBody(PtfIcons.SlotRef(2, 7)))
        assertTrue(PtfIcons.isBody(PtfIcons.SlotRef(3, 8)))
        assertTrue(!PtfIcons.isBody(PtfIcons.SlotRef(3, 9)))
        assertTrue(PtfIcons.isBody(PtfIcons.SlotRef(4, 0)))
        assertTrue(!PtfIcons.isBody(PtfIcons.SlotRef(4, 1)))
        assertTrue(!PtfIcons.isBody(PtfIcons.SlotRef(1, 0)))
        assertTrue(!PtfIcons.isBody(PtfIcons.SlotRef(5, 0)))
    }

    @Test
    fun `labels name the documented PSP records only`() {
        assertEquals("TV", PtfIcons.labelFor(PtfIcons.SlotRef(2, 5)))
        assertEquals("Extras", PtfIcons.labelFor(PtfIcons.SlotRef(2, 8)))
        assertEquals("Internet search", PtfIcons.labelFor(PtfIcons.SlotRef(3, 50)))
        assertEquals("Default category", PtfIcons.labelFor(PtfIcons.SlotRef(2, 0)))
        assertEquals("Default sub-item", PtfIcons.labelFor(PtfIcons.SlotRef(4, 0)))
        assertEquals(null, PtfIcons.labelFor(PtfIcons.SlotRef(3, 30)))
    }

    @Test
    fun `a stem round-trips only for canonical body refs`() {
        val ref = PtfIcons.SlotRef(3, 44)
        assertEquals("3_44", PtfIcons.fileStem(ref))
        assertEquals(ref, PtfIcons.refForStem("3_44"))
        assertEquals(PtfIcons.SlotRef(2, 7), PtfIcons.refForStem("2_7"))
        for (bad in listOf("3_9", "x", "3-44", "3_044", "03_44", "2_256", "2_-1", "1_0", "5_0", "3_", "_3", "")) {
            assertEquals(null, PtfIcons.refForStem(bad), "'$bad' is not a body stem")
        }
    }
}
