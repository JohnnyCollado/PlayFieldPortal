package com.playfieldportal.feature.achievements.provider.vita

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser for Vita3K's `TROPUSR.DAT` (per-game trophy progress). Little-endian throughout (PS Vita
 * is ARM LE — NOT the big-endian PS3 layout in apollo-ps3/RPCS3).
 *
 * Format reverse-engineered from real files (Disgaea 3, NPWR02979_00) and cross-validated against
 * `TROP.SFM`, with the unlock encoding confirmed across two captures whose unlock timestamps moved
 * with the play session (byte offsets, little-endian):
 *   0x00   u32       magic = 0x12D5819A
 *   0x14   u64       hiddenMask — bit i set = trophy i is hidden (matched TROP.SFM exactly)
 *   0x28   u32       trophyCount (also duplicated at 0x30)
 *   0x50   slot[i]   per-trophy unlock table, 16 bytes each, indexed by trophy id:
 *                      +0 u64 unlockTime (SECONDS since Unix epoch), +8 u64 reserved (0 observed).
 *                    Unlocked iff the slot is non-zero; unlockTime is the unlock instant.
 *   0x470  u32[]     gradeTable — one per trophy: 1=Platinum 2=Gold 3=Silver 4=Bronze
 */
object TropUsrParser {

    data class Trophy(
        val id: Int,
        val grade: Grade,
        val hidden: Boolean,
        val unlocked: Boolean,
        val unlockedAtEpochSec: Long?,
    )

    enum class Grade { PLATINUM, GOLD, SILVER, BRONZE, UNKNOWN }

    data class TropUsr(
        val trophyCount: Int,
        val trophies: List<Trophy>,
    ) {
        val unlockedCount: Int get() = trophies.count { it.unlocked }
    }

    private const val MAGIC = 0x12D5819A
    private const val OFF_HIDDEN_MASK = 0x14
    private const val OFF_COUNT = 0x28
    private const val OFF_UNLOCK_TABLE = 0x50   // per-trophy slots, 16 bytes each, id-indexed
    private const val UNLOCK_SLOT_SIZE = 16
    private const val OFF_GRADE_TABLE = 0x470
    private const val MAX_TROPHIES = 128        // Vita cap is 128 per set

    /** Returns null when [bytes] is not a recognizable TROPUSR.DAT (caller falls back to defs-only). */
    fun parse(bytes: ByteArray): TropUsr? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < OFF_COUNT + 4 || b.getInt(0) != MAGIC) return null

        val count = b.getInt(OFF_COUNT)
        if (count !in 1..MAX_TROPHIES) return null

        val unlockTableEnd = OFF_UNLOCK_TABLE + count * UNLOCK_SLOT_SIZE
        val gradeTableEnd = OFF_GRADE_TABLE + count * 4
        if (bytes.size < maxOf(unlockTableEnd, gradeTableEnd)) return null

        val hiddenMask = b.getLong(OFF_HIDDEN_MASK)
        val trophies = (0 until count).map { i ->
            val slot = OFF_UNLOCK_TABLE + i * UNLOCK_SLOT_SIZE
            val unlockTime = b.getLong(slot)                 // seconds since epoch, 0 = locked
            Trophy(
                id = i,
                grade = gradeOf(b.getInt(OFF_GRADE_TABLE + i * 4)),
                hidden = (hiddenMask ushr i) and 1L == 1L,
                unlocked = unlockTime != 0L,
                unlockedAtEpochSec = unlockTime.takeIf { it != 0L },
            )
        }
        return TropUsr(count, trophies)
    }

    private fun gradeOf(v: Int): Grade = when (v) {
        1 -> Grade.PLATINUM
        2 -> Grade.GOLD
        3 -> Grade.SILVER
        4 -> Grade.BRONZE
        else -> Grade.UNKNOWN
    }
}
