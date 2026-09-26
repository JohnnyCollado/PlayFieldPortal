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

/**
 * The fixture is **built in code** from the documented layout rather than committing the captured
 * `TROPUSR.DAT`: the real file carries an `Sce-Np-Trophy-Signature` and game metadata, and a
 * synthesized one documents the format in the test itself. The capture stays outside the repo for
 * manual cross-checking.
 */
class TropUsrPs3ParserTest {

    private data class Def(val id: Int, val grade: Int, val pid: Int)
    private data class Unlock(val id: Int, val unlocked: Boolean, val timeUs: Long)

    // magic/version/tocCount at 0, TOC entries of 32 B at 0x30, then blocks of 16 B header +
    // payload, back to back. `extraBlocks` inject unknown types to prove they are skipped.
    private fun tropUsr(
        defs: List<Def>,
        unlocks: List<Unlock>,
        extraBlocks: List<Pair<Int, Int>> = emptyList(),
        magic: Int = 0x818F54AD.toInt(),
        tocCount: Int = 2,
    ): ByteArray {
        val header = ByteBuffer.allocate(0x30 + tocCount * 32).order(ByteOrder.BIG_ENDIAN)
        header.putInt(0x00, magic)
        header.putInt(0x04, 0x00010000)
        header.putInt(0x08, tocCount)
        val blocksStart = 0x30 + tocCount * 32
        for (i in 0 until tocCount) {
            val e = 0x30 + i * 32
            header.putInt(e, if (i == 0) 4 else 6)
            header.putInt(e + 4, if (i == 0) 0x50 else 0x60)
            header.putInt(e + 8, 1)
            header.putInt(e + 12, 0x30)
            header.putInt(e + 20, blocksStart)   // section offset; blocks are contiguous
        }

        val out = ByteArrayOutputStream()
        out.write(header.array())
        fun block(type: Int, payloadSize: Int, fill: (ByteBuffer) -> Unit) {
            val b = ByteBuffer.allocate(16 + payloadSize).order(ByteOrder.BIG_ENDIAN)
            b.putInt(0, type)
            b.putInt(4, payloadSize)
            fill(b)
            out.write(b.array())
        }
        defs.forEach { d ->
            block(4, 0x50) { b ->
                b.putInt(8, d.id)                 // block index == trophy id
                b.putInt(16 + 0x00, d.id)
                b.putInt(16 + 0x04, d.grade)
                b.putInt(16 + 0x08, d.pid)
            }
        }
        extraBlocks.forEach { (type, size) -> block(type, size) { } }
        unlocks.forEach { u ->
            block(6, 0x60) { b ->
                b.putInt(8, u.id)
                b.putInt(16 + 0x00, u.id)
                b.putInt(16 + 0x04, if (u.unlocked) 1 else 0)
                b.putLong(16 + 0x10, u.timeUs)
                b.putLong(16 + 0x18, u.timeUs)    // the duplicate the capture always carried
            }
        }
        return out.toByteArray()
    }

    // 2026-09-12T05:50:00Z expressed as µs since year 1 — the CELL RTC epoch, not Unix.
    private val unlockUs = 62_135_596_800_000_000L + 1_789_192_200_000L * 1_000L

    @Test
    fun `reads grades, the platinum's parent id, and per-trophy unlock state`() {
        val bytes = tropUsr(
            defs = listOf(Def(0, 1, -1), Def(1, 2, 0), Def(2, 3, 0), Def(3, 4, 0)),
            unlocks = listOf(
                Unlock(0, false, 0L),
                Unlock(1, true, unlockUs),
                Unlock(2, false, 0L),
                Unlock(3, true, unlockUs),
            ),
        )

        val parsed = assertNotNull(TropUsrPs3Parser.parse(bytes))
        assertEquals(4, parsed.trophies.size)
        assertEquals(
            listOf(
                TropUsrParser.Grade.PLATINUM,
                TropUsrParser.Grade.GOLD,
                TropUsrParser.Grade.SILVER,
                TropUsrParser.Grade.BRONZE,
            ),
            parsed.trophies.map { it.grade },
        )
        // The platinum is the only trophy with no parent group.
        assertEquals(-1, parsed.trophies.first { it.grade == TropUsrParser.Grade.PLATINUM }.parentId)
        assertEquals(2, parsed.unlockedCount)
        assertFalse(parsed.trophies[0].unlocked)
        assertTrue(parsed.trophies[1].unlocked)
    }

    @Test
    fun `converts the CELL RTC epoch to unix millis, and leaves a locked trophy without a time`() {
        val bytes = tropUsr(
            defs = listOf(Def(0, 4, 0), Def(1, 4, 0)),
            unlocks = listOf(Unlock(0, true, unlockUs), Unlock(1, false, 0L)),
        )

        val parsed = assertNotNull(TropUsrPs3Parser.parse(bytes))
        assertEquals(1_789_192_200_000L, parsed.trophies[0].unlockedAtEpochMillis)
        assertNull(parsed.trophies[1].unlockedAtEpochMillis)
    }

    @Test
    fun `skips an unknown block type sitting between the known ones`() {
        val bytes = tropUsr(
            defs = listOf(Def(0, 1, -1), Def(1, 3, 0)),
            unlocks = listOf(Unlock(0, false, 0L), Unlock(1, true, unlockUs)),
            extraBlocks = listOf(9 to 0x20, 11 to 0x08),   // types this build has never seen
        )

        val parsed = assertNotNull(TropUsrPs3Parser.parse(bytes))
        assertEquals(2, parsed.trophies.size)
        assertEquals(1, parsed.unlockedCount)
    }

    @Test
    fun `rejects a file that isn't a TROPUSR - a bad magic returns null, not an empty set`() {
        val bytes = tropUsr(defs = listOf(Def(0, 4, 0)), unlocks = emptyList(), magic = 0x12D5819A)
        assertNull(TropUsrPs3Parser.parse(bytes))
        assertNull(TropUsrPs3Parser.parse(ByteArray(8)))
    }

    @Test
    fun `a truncated file keeps the blocks that did parse and stops at the cut`() {
        val full = tropUsr(
            defs = listOf(Def(0, 1, -1), Def(1, 2, 0), Def(2, 4, 0)),
            unlocks = listOf(Unlock(0, false, 0L), Unlock(1, true, unlockUs), Unlock(2, false, 0L)),
        )
        // Cut mid-way through the second definition block (0x70 header + 0x60 stride each).
        val truncated = full.copyOf(0x70 + 0x60 + 0x20)

        val parsed = assertNotNull(TropUsrPs3Parser.parse(truncated))
        assertEquals(1, parsed.trophies.size)
        assertEquals(TropUsrParser.Grade.PLATINUM, parsed.trophies[0].grade)
        // No type-6 block survived the cut, so nothing is claimed as earned.
        assertEquals(0, parsed.unlockedCount)
    }

    @Test
    fun `a definitions-only file tracks at zero rather than reporting unknown`() {
        val bytes = tropUsr(defs = listOf(Def(0, 1, -1), Def(1, 4, 0)), unlocks = emptyList())

        val parsed = assertNotNull(TropUsrPs3Parser.parse(bytes))
        assertEquals(2, parsed.trophies.size)
        assertEquals(0, parsed.unlockedCount)
        assertTrue(parsed.trophies.none { it.unlocked })
    }

    @Test
    fun `the block walk consumes the file exactly, as the real capture did`() {
        // 48 trophies: 0x70 header + 48 * (16 + 0x50) + 48 * (16 + 0x60) = 0x2770, the capture's size.
        val defs = (0 until 48).map { Def(it, if (it == 0) 1 else 4, if (it == 0) -1 else 0) }
        val bytes = tropUsr(defs, defs.map { Unlock(it.id, false, 0L) })

        assertEquals(0x2770, bytes.size)
        assertEquals(48, assertNotNull(TropUsrPs3Parser.parse(bytes)).trophies.size)
    }
}
