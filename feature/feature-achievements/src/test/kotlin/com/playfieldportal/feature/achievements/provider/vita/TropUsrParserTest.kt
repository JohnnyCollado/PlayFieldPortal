package com.playfieldportal.feature.achievements.provider.vita

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TropUsrParserTest {

    // Builds a minimal TROPUSR.DAT in the confirmed layout: magic @0, hidden mask @0x14,
    // count @0x28, per-trophy unlock slots (16 B) @0x50, grade table (u32) @0x470.
    private fun tropUsr(count: Int, hiddenMask: Long, unlockTimes: LongArray, grades: IntArray): ByteArray {
        val size = maxOf(0x50 + count * 16, 0x470 + count * 4)
        val b = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0x00, 0x12D5819A.toInt())
        b.putLong(0x14, hiddenMask)
        b.putInt(0x28, count)
        for (i in 0 until count) b.putLong(0x50 + i * 16, unlockTimes[i])
        for (i in 0 until count) b.putInt(0x470 + i * 4, grades[i])
        return b.array()
    }

    @Test
    fun `parses grades, hidden flags, and per-trophy unlock state`() {
        val t = 1785078742L                 // A Single Step's real unlock second
        val bytes = tropUsr(
            count = 3,
            hiddenMask = 0b010L,             // only trophy 1 is hidden
            unlockTimes = longArrayOf(t, 0L, t + 100),   // 0 and 2 unlocked, 1 locked
            grades = intArrayOf(1, 4, 2),    // Platinum, Bronze, Gold
        )

        val r = TropUsrParser.parse(bytes)!!

        assertEquals(3, r.trophyCount)
        assertEquals(2, r.unlockedCount)

        assertEquals(TropUsrParser.Grade.PLATINUM, r.trophies[0].grade)
        assertTrue(r.trophies[0].unlocked)
        assertEquals(t, r.trophies[0].unlockedAtEpochSec)
        assertFalse(r.trophies[0].hidden)

        assertEquals(TropUsrParser.Grade.BRONZE, r.trophies[1].grade)
        assertFalse(r.trophies[1].unlocked)
        assertNull(r.trophies[1].unlockedAtEpochSec)
        assertTrue(r.trophies[1].hidden)

        assertEquals(TropUsrParser.Grade.GOLD, r.trophies[2].grade)
        assertTrue(r.trophies[2].unlocked)
    }

    @Test
    fun `rejects a file with the wrong magic or too small`() {
        assertNull(TropUsrParser.parse(ByteArray(2000)))         // right size, zero magic
        assertNull(TropUsrParser.parse(byteArrayOf(1, 2, 3, 4))) // too small
    }
}
