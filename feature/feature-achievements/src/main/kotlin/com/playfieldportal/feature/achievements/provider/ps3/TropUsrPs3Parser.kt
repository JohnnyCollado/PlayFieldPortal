package com.playfieldportal.feature.achievements.provider.ps3

import com.playfieldportal.feature.achievements.provider.vita.TropUsrParser
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parser for ARMSX3's (RPCS3's) PS3 `TROPUSR.DAT` — one file per trophy set, holding both a mirror
 * of the trophy definitions and the player's unlock state. **Big-endian** throughout, unlike the
 * little-endian Vita file in [TropUsrParser], and laid out as a table of contents plus walkable
 * typed blocks rather than fixed offsets:
 *
 *   0x00  u32   magic = 0x818F54AD
 *   0x04  u32   version
 *   0x08  u32   TOC entry count
 *   0x30  TOC entries, 32 bytes each: type, payloadSize, 1, headerEnd, 0, sectionOffset, 0, 0
 *   then  blocks, back to back: u32 type, u32 payloadSize, u32 index, u32 pad, then the payload.
 *         Stride is `16 + payloadSize`, and the blocks consume the file exactly.
 *
 *   type 4 (payload 0x50) — definitions mirror: i32 id, i32 grade, i32 parent group id (-1 = platinum)
 *   type 6 (payload 0x60) — unlock state:      u32 id, u32 unlocked, then u64 unlockTime at +0x10
 *
 * Only types 4 and 6 were observed, so the walk **skips** any other type rather than assuming the
 * set is fixed: a DLC subset or a different game may carry blocks this build has never seen, and
 * skipping an unknown block is the difference between surviving an unseen set and corrupting one.
 *
 * Timestamps are **microseconds since 0001-01-01T00:00:00Z** (the CELL RTC / PSN epoch), not Unix.
 * Grade codes are identical to Vita's, so [TropUsrParser.Grade] is shared.
 */
object TropUsrPs3Parser {

    data class Trophy(
        val id: Int,
        val grade: TropUsrParser.Grade,
        /** Parent trophy group, `-1` for the platinum. Carried for fidelity; not yet surfaced. */
        val parentId: Int,
        val unlocked: Boolean,
        val unlockedAtEpochMillis: Long?,
    )

    data class TropUsr(val trophies: List<Trophy>) {
        val unlockedCount: Int get() = trophies.count { it.unlocked }
    }

    /** µs between 0001-01-01 and 1970-01-01 — the CELL RTC epoch offset. */
    const val CELL_EPOCH_OFFSET_US = 62_135_596_800_000_000L

    private const val MAGIC = 0x818F54AD.toInt()
    private const val OFF_TOC_COUNT = 0x08
    private const val OFF_TOC = 0x30
    private const val TOC_ENTRY_SIZE = 32
    private const val TOC_SECTION_OFFSET = 20     // within a TOC entry
    private const val BLOCK_HEADER_SIZE = 16
    private const val TYPE_DEFINITION = 4
    private const val TYPE_UNLOCK = 6
    private const val MAX_TOC_ENTRIES = 64
    private const val MAX_BLOCKS = 4_096          // ~2k trophies across both block types
    private const val MAX_PAYLOAD = 64 * 1024

    /** Returns null when [bytes] is not a recognizable PS3 `TROPUSR.DAT` — the caller then falls
     *  back to definitions-only rather than reporting "nothing earned". */
    fun parse(bytes: ByteArray): TropUsr? {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (bytes.size < OFF_TOC + TOC_ENTRY_SIZE || b.getInt(0) != MAGIC) return null

        val tocCount = b.getInt(OFF_TOC_COUNT)
        if (tocCount !in 1..MAX_TOC_ENTRIES) return null
        val tocEnd = OFF_TOC + tocCount * TOC_ENTRY_SIZE
        if (bytes.size < tocEnd) return null

        // Blocks sit back to back after the TOC, so the walk starts at the first section offset the
        // TOC declares (falling back to the end of the TOC when those offsets are unusable).
        val declaredStart = (0 until tocCount)
            .map { b.getInt(OFF_TOC + it * TOC_ENTRY_SIZE + TOC_SECTION_OFFSET) }
            .filter { it in tocEnd..bytes.size }
            .minOrNull()
        var offset = declaredStart ?: tocEnd

        val grades = LinkedHashMap<Int, Pair<TropUsrParser.Grade, Int>>()
        val unlocks = HashMap<Int, Pair<Boolean, Long?>>()
        var blocks = 0
        while (offset + BLOCK_HEADER_SIZE <= bytes.size && blocks++ < MAX_BLOCKS) {
            val type = b.getInt(offset)
            val payloadSize = b.getInt(offset + 4)
            if (payloadSize < 0 || payloadSize > MAX_PAYLOAD) break
            val payload = offset + BLOCK_HEADER_SIZE
            // A block whose stride runs past the buffer is a truncated file: stop, keep what parsed.
            if (payload + payloadSize > bytes.size) break

            when (type) {
                TYPE_DEFINITION -> if (payloadSize >= 12) {
                    val id = b.getInt(payload)
                    if (id >= 0) grades[id] = gradeOf(b.getInt(payload + 4)) to b.getInt(payload + 8)
                }
                TYPE_UNLOCK -> if (payloadSize >= 0x18) {
                    val id = b.getInt(payload)
                    val unlocked = b.getInt(payload + 4) != 0
                    val timeUs = b.getLong(payload + 0x10)
                    if (id >= 0) unlocks[id] = unlocked to unixMillisOf(timeUs)
                }
                else -> Unit   // an unseen block type is skipped, never guessed at
            }
            offset = payload + payloadSize
        }
        // Null only when nothing recognizable parsed at all. A file carrying one kind of block but
        // not the other is still usable: grades also come from TROPCONF.SFM, which is authoritative.
        if (grades.isEmpty() && unlocks.isEmpty()) return null

        val trophies = (grades.keys + unlocks.keys).sorted().map { id ->
            val def = grades[id]
            val unlock = unlocks[id]
            Trophy(
                id = id,
                grade = def?.first ?: TropUsrParser.Grade.UNKNOWN,
                parentId = def?.second ?: 0,
                unlocked = unlock?.first ?: false,
                // A time without the unlocked flag is not an unlock — the flag governs.
                unlockedAtEpochMillis = unlock?.second?.takeIf { unlock.first },
            )
        }
        return TropUsr(trophies)
    }

    /** µs since year 1 as Unix millis; null for 0 (never unlocked) or a pre-1970 nonsense value. */
    private fun unixMillisOf(timeUs: Long): Long? {
        if (timeUs <= 0L) return null
        val unixUs = timeUs - CELL_EPOCH_OFFSET_US
        return if (unixUs <= 0L) null else unixUs / 1_000
    }

    private fun gradeOf(v: Int): TropUsrParser.Grade = when (v) {
        1 -> TropUsrParser.Grade.PLATINUM
        2 -> TropUsrParser.Grade.GOLD
        3 -> TropUsrParser.Grade.SILVER
        4 -> TropUsrParser.Grade.BRONZE
        else -> TropUsrParser.Grade.UNKNOWN
    }
}
