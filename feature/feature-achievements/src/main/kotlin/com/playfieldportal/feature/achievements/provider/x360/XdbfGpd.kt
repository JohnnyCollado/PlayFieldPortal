package com.playfieldportal.feature.achievements.provider.x360

/**
 * Reads Xenia's profile GPD files — the XDBF database the Xbox 360 dashboard keeps per profile,
 * which X360 Mobile and XenDroid (both Xenia ports) write exactly as Xenia Canary does.
 *
 * A profile folder (`content/<XUID>/FFFE07D1/00010000/<XUID>/`) holds one `<TitleID>.gpd` per game
 * played — every achievement the game declares, earned or not — plus the dashboard's own
 * `FFFE07D1.gpd`, whose title records list each game played. A title GPD only appears once the game
 * has run: Xenia copies the game's achievement table in on first boot, all locked.
 *
 * Layout (all big-endian): a 24-byte header (`"XDBF"`, version, entry count/used, free count/used),
 * an 18-byte-per-entry table (`u16 section, u64 id, u32 offset, u32 size`), an 8-byte-per-entry
 * free table, then the data, which entry offsets are relative to.
 *
 * Pure and Android-free so it can be tested against real files pulled off a device.
 */
object XdbfGpd {

    const val SECTION_ACHIEVEMENT = 1
    const val SECTION_IMAGE = 2
    const val SECTION_TITLE = 4
    const val SECTION_STRING = 5

    /** Image and string id of the game's own icon and name inside its title GPD. */
    const val TITLE_IMAGE_ID = 0x8000L
    const val TITLE_STRING_ID = 0x8000L

    /** Achievement flag: earned. */
    const val FLAG_ACHIEVED = 0x20000

    /** Achievement flag: the description shows before it is earned (otherwise it is secret). */
    const val FLAG_SHOW_UNACHIEVED = 0x8

    private const val HEADER_SIZE = 24
    private const val ENTRY_SIZE = 18
    private const val FREE_ENTRY_SIZE = 8
    private const val ACHIEVEMENT_STRUCT_SIZE = 0x1C
    private const val TITLE_STRUCT_SIZE = 0x28
    private const val MAX_ENTRIES = 0x10000

    // Ids at or above this are sync bookkeeping (0x100000000 / 0x200000000), never content.
    private const val SYNC_ID_FLOOR = 0x100000000L

    // 100 ns FILETIME ticks between 1601-01-01 and the Unix epoch, in milliseconds.
    private const val FILETIME_EPOCH_OFFSET_MS = 11_644_473_600_000L

    data class Achievement(
        val id: Int,
        val imageId: Int,
        val gamerscore: Int,
        val flags: Int,
        val title: String,
        val unlockedDescription: String,
        val lockedDescription: String,
        val unlockedAtEpochMillis: Long?,
    ) {
        val unlocked: Boolean get() = flags and FLAG_ACHIEVED != 0
        val secret: Boolean get() = flags and FLAG_SHOW_UNACHIEVED == 0
    }

    /** One game the profile has played, from the dashboard GPD. */
    data class TitlePlayed(
        val titleId: String,
        val name: String,
        val achievementsTotal: Int,
        val achievementsUnlocked: Int,
        val gamerscoreTotal: Int,
        val gamerscoreEarned: Int,
        val lastPlayedEpochMillis: Long?,
    )

    class Gpd(
        val achievements: List<Achievement>,
        val images: Map<Long, ByteArray>,
        val strings: Map<Long, String>,
        val titles: List<TitlePlayed>,
    ) {
        /** The game's name, from a title GPD; null in the dashboard GPD. */
        val titleName: String? get() = strings[TITLE_STRING_ID]
    }

    /** Parses [bytes], or null when they are not an XDBF file. A damaged entry is skipped, never thrown. */
    fun parse(bytes: ByteArray): Gpd? {
        if (bytes.size < HEADER_SIZE) return null
        if (bytes[0] != 'X'.code.toByte() || bytes[1] != 'D'.code.toByte() ||
            bytes[2] != 'B'.code.toByte() || bytes[3] != 'F'.code.toByte()
        ) return null
        val entryCount = u32(bytes, 8)
        val entryUsed = u32(bytes, 12)
        val freeCount = u32(bytes, 16)
        if (entryCount !in 0..MAX_ENTRIES || freeCount !in 0..MAX_ENTRIES || entryUsed !in 0..entryCount) return null
        val dataStart = HEADER_SIZE.toLong() + ENTRY_SIZE.toLong() * entryCount + FREE_ENTRY_SIZE.toLong() * freeCount
        if (dataStart > bytes.size) return null

        val achievements = mutableListOf<Achievement>()
        val images = mutableMapOf<Long, ByteArray>()
        val strings = mutableMapOf<Long, String>()
        val titles = mutableListOf<TitlePlayed>()
        for (i in 0 until entryUsed) {
            val at = HEADER_SIZE + i * ENTRY_SIZE
            if (at + ENTRY_SIZE > bytes.size) break
            val section = u16(bytes, at)
            val id = u64(bytes, at + 2)
            val offset = u32(bytes, at + 10)
            val size = u32(bytes, at + 14)
            if (id >= SYNC_ID_FLOOR || offset < 0 || size <= 0) continue
            val start = dataStart + offset
            if (start + size > bytes.size) continue
            val data = bytes.copyOfRange(start.toInt(), (start + size).toInt())
            runCatching {
                when (section) {
                    SECTION_ACHIEVEMENT -> achievementOf(data)?.let(achievements::add)
                    SECTION_IMAGE -> images[id] = data
                    SECTION_STRING -> strings[id] = utf16(data, 0).first
                    SECTION_TITLE -> titleOf(data)?.let(titles::add)
                }
            }
        }
        return Gpd(achievements, images, strings, titles)
    }

    /** Windows FILETIME (100 ns since 1601) to epoch millis; null for the "never" value 0. */
    fun epochMillisOf(filetime: Long): Long? =
        if (filetime <= 0L) null else filetime / 10_000L - FILETIME_EPOCH_OFFSET_MS

    private fun achievementOf(d: ByteArray): Achievement? {
        if (d.size < ACHIEVEMENT_STRUCT_SIZE) return null
        val flags = u32(d, 16)
        val (title, afterTitle) = utf16(d, ACHIEVEMENT_STRUCT_SIZE)
        val (unlocked, afterUnlocked) = utf16(d, afterTitle)
        val (locked, _) = utf16(d, afterUnlocked)
        return Achievement(
            id = u32(d, 4),
            imageId = u32(d, 8),
            gamerscore = u32(d, 12),
            flags = flags,
            title = title,
            unlockedDescription = unlocked,
            lockedDescription = locked,
            // Earned without a time happens (offline unlocks); a time on a locked one is noise.
            unlockedAtEpochMillis = if (flags and FLAG_ACHIEVED != 0) epochMillisOf(u64(d, 20)) else null,
        )
    }

    private fun titleOf(d: ByteArray): TitlePlayed? {
        if (d.size < TITLE_STRUCT_SIZE) return null
        return TitlePlayed(
            titleId = "%08X".format(u32(d, 0)),
            achievementsTotal = u32(d, 4),
            achievementsUnlocked = u32(d, 8),
            gamerscoreTotal = u32(d, 12),
            gamerscoreEarned = u32(d, 16),
            lastPlayedEpochMillis = epochMillisOf(u64(d, 0x20)),
            name = utf16(d, TITLE_STRUCT_SIZE).first,
        )
    }

    // A null-terminated UTF-16BE string at [from]; returns it and the offset just past its terminator.
    private fun utf16(d: ByteArray, from: Int): Pair<String, Int> {
        if (from >= d.size) return "" to d.size
        var end = from
        while (end + 1 < d.size && (d[end].toInt() != 0 || d[end + 1].toInt() != 0)) end += 2
        val text = String(d, from, minOf(end, d.size) - from, Charsets.UTF_16BE)
        return text to minOf(end + 2, d.size)
    }

    private fun u16(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun u32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun u64(b: ByteArray, o: Int): Long =
        (u32(b, o).toLong() shl 32) or (u32(b, o + 4).toLong() and 0xFFFFFFFFL)
}
