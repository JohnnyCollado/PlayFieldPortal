package com.playfieldportal.feature.achievements.provider.ps3

import com.playfieldportal.feature.achievements.provider.vita.TropUsrParser
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The rules of section 5: namespaced coin ids, TROPDIR order, and exactly one platinum. */
class Ps3TrophySetsTest {

    private fun sfm(npCommId: String, titleName: String, trophies: List<Triple<Int, String, String>>): ByteArray {
        val body = trophies.joinToString("\n") { (id, ttype, name) ->
            """ <trophy id="%03d" hidden="%s" ttype="$ttype" pid="${if (ttype == "P") -1 else 0}">
  <name>$name</name>
  <detail>Detail for $name</detail>
 </trophy>""".format(id, if (id == 2) "yes" else "no")
        }
        return """<?xml version="1.0"?>
<trophyconf version="1.1" policy="large">
 <npcommid>$npCommId</npcommid>
 <title-name>$titleName</title-name>
$body
</trophyconf>
""".toByteArray()
    }

    // A minimal TROPUSR.DAT in the documented big-endian TOC+blocks layout.
    private fun tropUsr(unlocked: Set<Int>, count: Int, timeUs: Long = 62_135_596_800_000_000L + 1_000_000L): ByteArray {
        val header = ByteBuffer.allocate(0x30 + 2 * 32).order(ByteOrder.BIG_ENDIAN)
        header.putInt(0x00, 0x818F54AD.toInt())
        header.putInt(0x08, 2)
        header.putInt(0x30 + 20, 0x70)
        header.putInt(0x30 + 32 + 20, 0x70)
        val out = ByteArrayOutputStream().apply { write(header.array()) }
        for (id in 0 until count) {
            val b = ByteBuffer.allocate(16 + 0x50).order(ByteOrder.BIG_ENDIAN)
            b.putInt(0, 4); b.putInt(4, 0x50); b.putInt(16, id); b.putInt(20, 4); b.putInt(24, 0)
            out.write(b.array())
        }
        for (id in 0 until count) {
            val b = ByteBuffer.allocate(16 + 0x60).order(ByteOrder.BIG_ENDIAN)
            b.putInt(0, 6); b.putInt(4, 0x60); b.putInt(16, id)
            b.putInt(20, if (id in unlocked) 1 else 0)
            if (id in unlocked) b.putLong(16 + 0x10, timeUs)
            out.write(b.array())
        }
        return out.toByteArray()
    }

    @Test
    fun `joins definitions with unlock state, taking hidden only from the SFM`() {
        val set = assertNotNull(
            Ps3TrophySets.buildSet(
                npCommId = "NPWR05915_00",
                sfmBytes = sfm(
                    "NPWR05915_00", "The Witch and the Hundred Knight",
                    listOf(Triple(0, "P", "Grand Finale"), Triple(1, "B", "First Step"), Triple(2, "S", "Secret")),
                ),
                usrBytes = tropUsr(unlocked = setOf(1), count = 3),
                iconUriFor = { name -> "content://icons/$name" },
            ),
        )

        assertEquals("The Witch and the Hundred Knight", set.titleName)
        assertEquals(TropUsrParser.Grade.PLATINUM, set.trophies[0].grade)
        assertTrue(set.trophies[1].unlocked)
        assertFalse(set.trophies[0].unlocked)
        // hidden="yes" lives only in TROPCONF.SFM — the PS3 DAT carries no hidden mask.
        assertTrue(set.trophies[2].hidden)
        assertFalse(set.trophies[1].hidden)
        assertEquals("content://icons/TROP001.PNG", set.trophies[1].iconUri)
    }

    @Test
    fun `a set with no TROPUSR loads definitions-only at zero percent`() {
        val set = assertNotNull(
            Ps3TrophySets.buildSet(
                "NPWR05915_00",
                sfm("NPWR05915_00", "Game", listOf(Triple(0, "P", "Plat"), Triple(1, "B", "Bronze"))),
                usrBytes = null,
            ) { null },
        )

        assertEquals(2, set.trophies.size)
        assertTrue(set.trophies.none { it.unlocked })
        assertTrue(set.trophies.all { it.unlockedAtEpochMillis == null })
    }

    @Test
    fun `a missing icon leaves the uri null rather than inventing one`() {
        val set = assertNotNull(
            Ps3TrophySets.buildSet(
                "NPWR05915_00",
                sfm("NPWR05915_00", "Game", listOf(Triple(0, "B", "One"), Triple(1, "B", "Two"))),
                usrBytes = null,
            ) { name -> "content://icons/$name".takeIf { name == "TROP000.PNG" } },
        )

        assertEquals("content://icons/TROP000.PNG", set.trophies[0].iconUri)
        assertNull(set.trophies[1].iconUri)
    }

    @Test
    fun `an SFM with no trophies is not a set`() {
        assertNull(Ps3TrophySets.buildSet("NPWR05915_00", sfm("NPWR05915_00", "Game", emptyList()), null) { null })
    }

    @Test
    fun `two subsets numbering from zero produce distinct coin ids`() {
        val base = assertNotNull(
            Ps3TrophySets.buildSet(
                "NPWR05915_00",
                sfm("NPWR05915_00", "Game", listOf(Triple(0, "P", "Plat"), Triple(3, "B", "Base three"))),
                null,
            ) { null },
        )
        val dlc = assertNotNull(
            Ps3TrophySets.buildSet(
                "NPWR05915_01",
                sfm("NPWR05915_01", "Game DLC", listOf(Triple(0, "B", "DLC zero"), Triple(3, "G", "DLC three"))),
                null,
            ) { null },
        )

        val merged = Ps3TrophySets.merge(listOf(base, dlc))
        assertEquals(4, merged.size)
        assertEquals(4, merged.map { it.coinId }.distinct().size)
        assertEquals(
            listOf("NPWR05915_00:0", "NPWR05915_00:3", "NPWR05915_01:0", "NPWR05915_01:3"),
            merged.map { it.coinId },
        )
        // Base-set trophies come first and keep their own titles — nothing overwrote trophy 3.
        assertEquals("Base three", merged[1].name)
        assertEquals("DLC three", merged[3].name)
    }

    @Test
    fun `merge order follows the input list, not the set ids`() {
        fun set(id: String) = assertNotNull(
            Ps3TrophySets.buildSet(id, sfm(id, id, listOf(Triple(0, "B", "$id coin"))), null) { null },
        )

        val merged = Ps3TrophySets.merge(listOf(set("NPWR99999_02"), set("NPWR00001_00")))
        assertEquals(listOf("NPWR99999_02:0", "NPWR00001_00:0"), merged.map { it.coinId })
    }

    @Test
    fun `exactly one platinum survives even when a subset claims one`() {
        fun platSet(id: String) = assertNotNull(
            Ps3TrophySets.buildSet(id, sfm(id, id, listOf(Triple(0, "P", "$id plat"))), null) { null },
        )

        val merged = Ps3TrophySets.merge(listOf(platSet("NPWR05915_00"), platSet("NPWR05915_01")))
        assertEquals(1, merged.count { it.grade == TropUsrParser.Grade.PLATINUM })
        // The base set keeps the crown; the subset's claim is demoted rather than trusted.
        assertEquals("NPWR05915_00:0", merged.first { it.grade == TropUsrParser.Grade.PLATINUM }.coinId)
        assertEquals(TropUsrParser.Grade.GOLD, merged[1].grade)
    }

    @Test
    fun `a declared-but-absent subset simply contributes nothing`() {
        val base = assertNotNull(
            Ps3TrophySets.buildSet("NPWR05915_00", sfm("NPWR05915_00", "Game", listOf(Triple(0, "P", "Plat"))), null) { null },
        )

        // Discovery drops an absent set before the merge ever sees it (loadMerged uses mapNotNull).
        val merged = Ps3TrophySets.merge(listOf(base))
        assertEquals(1, merged.size)
        assertEquals("NPWR05915_00:0", merged[0].coinId)
    }
}
