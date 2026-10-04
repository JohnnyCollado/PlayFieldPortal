package com.playfieldportal.feature.achievements.provider.x360

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Xenia profile GPD reader. The two real fixtures came off a device running XenDroid: DOA4's
 * title GPD (45 achievements, none earned yet) and the profile's dashboard GPD. Unlocks are built
 * synthetically with [GpdFixture], since neither real file has one.
 */
class XdbfGpdTest {

    private fun resource(name: String) = javaClass.getResource("/x360/$name")!!.readBytes()

    @Test
    fun `reads every achievement and the title name from a real title GPD`() {
        val gpd = assertNotNull(XdbfGpd.parse(resource("doa4_544307D1.gpd")))

        assertEquals(45, gpd.achievements.size)
        assertEquals("DEAD OR ALIVE 4", gpd.titleName)
        val first = gpd.achievements.first { it.id == 6 }
        assertEquals("Completed Story Mode", first.title)
        assertEquals("Completed Story Mode once.", first.unlockedDescription)
        assertEquals("Play the Story Mode.", first.lockedDescription)
        assertEquals(10, first.gamerscore)
        assertFalse(first.unlocked)
        assertNull(first.unlockedAtEpochMillis)
    }

    @Test
    fun `an achievement without the show-unachieved flag is secret`() {
        val gpd = assertNotNull(XdbfGpd.parse(resource("doa4_544307D1.gpd")))

        assertFalse(gpd.achievements.first { it.id == 6 }.secret)   // flags 0x9
        assertTrue(gpd.achievements.first { it.id == 26 }.secret)   // flags 0x3, Unlocked "Gen Fu"
    }

    @Test
    fun `keeps the PNG images by image id - the title icon is 0x8000`() {
        val gpd = assertNotNull(XdbfGpd.parse(resource("doa4_544307D1.gpd")))

        val icon = assertNotNull(gpd.images[XdbfGpd.TITLE_IMAGE_ID])
        assertContentEquals(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), icon.copyOf(4))
    }

    @Test
    fun `reads the titles played from a real dashboard GPD`() {
        val gpd = assertNotNull(XdbfGpd.parse(resource("dashboard_FFFE07D1.gpd")))

        assertEquals(listOf("41560855", "544307D1"), gpd.titles.map { it.titleId }.sorted())
        val doa = gpd.titles.first { it.titleId == "544307D1" }
        assertEquals("DEAD OR ALIVE 4", doa.name)
        assertEquals(45, doa.achievementsTotal)
        assertEquals(0, doa.achievementsUnlocked)
        assertEquals(1000, doa.gamerscoreTotal)
        assertNotNull(doa.lastPlayedEpochMillis)
        assertTrue(gpd.achievements.isEmpty())
    }

    @Test
    fun `an earned achievement carries its unlock time in epoch millis`() {
        // 2026-10-04T03:02:37Z as a Windows FILETIME.
        val epochMillis = 1_791_082_957_000L
        val bytes = GpdFixture()
            .achievement(id = 1, imageId = 11, gamerscore = 15, flags = 0x9 or XdbfGpd.FLAG_ACHIEVED,
                unlockFiletime = GpdFixture.filetimeOf(epochMillis), title = "Death to Dictators")
            .image(11, GpdFixture.PNG)
            .build()

        val ach = assertNotNull(XdbfGpd.parse(bytes)).achievements.single()

        assertTrue(ach.unlocked)
        assertEquals(epochMillis, ach.unlockedAtEpochMillis)
        assertEquals(11, ach.imageId)
    }

    @Test
    fun `an earned flag with no timestamp is earned without a time`() {
        val bytes = GpdFixture()
            .achievement(id = 3, flags = XdbfGpd.FLAG_ACHIEVED, unlockFiletime = 0, title = "Offline")
            .build()

        val ach = assertNotNull(XdbfGpd.parse(bytes)).achievements.single()

        assertTrue(ach.unlocked)
        assertNull(ach.unlockedAtEpochMillis)
    }

    @Test
    fun `sync bookkeeping entries are not achievements`() {
        val bytes = GpdFixture()
            .achievement(id = 1, title = "Real")
            .raw(section = 1, id = 0x100000000L, data = ByteArray(16))
            .raw(section = 1, id = 0x200000000L, data = ByteArray(8))
            .build()

        assertEquals(listOf(1), assertNotNull(XdbfGpd.parse(bytes)).achievements.map { it.id })
    }

    @Test
    fun `something that is not XDBF reads as null`() {
        assertNull(XdbfGpd.parse("not a gpd file at all, really".encodeToByteArray()))
        assertNull(XdbfGpd.parse(ByteArray(0)))
    }

    @Test
    fun `a truncated file never throws`() {
        val whole = resource("doa4_544307D1.gpd")
        for (cut in listOf(10, 24, 100, 2_000, whole.size / 2)) {
            // Whatever survives is fine; the reader just must not crash on a half-written file.
            XdbfGpd.parse(whole.copyOf(cut))
        }
    }
}

/** Builds small XDBF files: header, entry table, empty free table, then each entry's data. */
internal class GpdFixture {
    private class Entry(val section: Int, val id: Long, val data: ByteArray)
    private val entries = mutableListOf<Entry>()

    fun achievement(
        id: Int,
        imageId: Int = id,
        gamerscore: Int = 10,
        flags: Int = 0x9,
        unlockFiletime: Long = 0,
        title: String = "Achievement $id",
        unlocked: String = "Did it",
        locked: String = "Do it",
    ) = apply {
        val out = java.io.ByteArrayOutputStream()
        val head = java.nio.ByteBuffer.allocate(0x1C)
            .putInt(0x1C).putInt(id).putInt(imageId).putInt(gamerscore).putInt(flags).putLong(unlockFiletime)
        out.write(head.array())
        for (s in listOf(title, unlocked, locked)) {
            out.write(s.toByteArray(Charsets.UTF_16BE))
            out.write(byteArrayOf(0, 0))
        }
        entries += Entry(1, id.toLong(), out.toByteArray())
    }

    fun image(id: Int, png: ByteArray) = apply { entries += Entry(2, id.toLong(), png) }

    fun string(id: Long, text: String) = apply {
        entries += Entry(5, id, text.toByteArray(Charsets.UTF_16BE) + byteArrayOf(0, 0))
    }

    fun raw(section: Int, id: Long, data: ByteArray) = apply { entries += Entry(section, id, data) }

    fun build(): ByteArray {
        val count = entries.size
        val freeCount = 1
        val table = java.nio.ByteBuffer.allocate(24 + 18 * count + 8 * freeCount)
            .put("XDBF".encodeToByteArray()).putInt(0x10000)
            .putInt(count).putInt(count).putInt(freeCount).putInt(0)
        var offset = 0
        for (e in entries) {
            table.putShort(e.section.toShort()).putLong(e.id).putInt(offset).putInt(e.data.size)
            offset += e.data.size
        }
        table.putInt(0).putInt(0)
        return table.array() + entries.fold(ByteArray(0)) { acc, e -> acc + e.data }
    }

    companion object {
        val PNG = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

        /** Epoch millis to a Windows FILETIME (100 ns ticks since 1601). */
        fun filetimeOf(epochMillis: Long): Long = (epochMillis + 11_644_473_600_000L) * 10_000L
    }
}
