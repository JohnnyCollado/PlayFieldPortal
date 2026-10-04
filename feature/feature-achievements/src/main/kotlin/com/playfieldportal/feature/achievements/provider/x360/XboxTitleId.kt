package com.playfieldportal.feature.achievements.provider.x360

import com.playfieldportal.feature.achievements.match.DiscImage

/**
 * Reads an Xbox 360 game's title ID — the 8-hex-digit id its profile GPD is named after — from the
 * game's own file, the same way Xenia does at boot:
 *  - a bare `default.xex`: the XEX2 execution-info optional header;
 *  - an STFS package (`CON `/`LIVE`/`PIRS`: Games on Demand, XBLA): the execution info in the
 *    package header's metadata;
 *  - an XDVDFS disc image: `default.xex` found through the root directory, then as above.
 *
 * Only headers are read — a few sectors of a multi-GB image. The XEX header is never encrypted, so
 * this works on any dump the emulators themselves can boot. Pure; tested with synthetic images.
 */
object XboxTitleId {

    /** XEX2 optional-header key for the execution info block. */
    const val EXECUTION_INFO_KEY = 0x00040006

    private const val SECTOR = 2048L
    private const val XDVDFS_MAGIC = "MICROSOFT*XBOX*MEDIA"

    // Where the game partition starts in each disc layout: trimmed XISO, XGD3, XGD2, XGD1.
    private val PARTITION_OFFSETS = longArrayOf(0L, 0x2080000L, 0xFD90000L, 0x18300000L)

    private const val STFS_TITLE_ID_OFFSET = 0x360
    private const val XEX_HEADER_READ = 64 * 1024
    private const val MAX_DIR_SIZE = 256 * 1024

    /** The title ID in an XEX2 header (from its first bytes), or null. */
    fun fromXex(header: ByteArray): String? {
        if (header.size < 0x18 || !header.startsWith("XEX2")) return null
        val count = u32(header, 0x14)
        if (count !in 1..256) return null
        for (i in 0 until count) {
            val at = 0x18 + i * 8
            if (at + 8 > header.size) return null
            if (u32(header, at) != EXECUTION_INFO_KEY) continue
            val info = u32(header, at + 4)
            // media id, version, base version, then the title id.
            if (info < 0 || info + 0x10 > header.size) return null
            return titleIdString(u32(header, info + 0xC))
        }
        return null
    }

    /** The title ID of whatever Xbox 360 game file [source] holds, or null when it can't tell. */
    fun fromSource(source: DiscImage.SeekableSource): String? {
        val head = read(source, 0, 0x400) ?: return null
        if (head.startsWith("XEX2")) return fromXex(read(source, 0, XEX_HEADER_READ) ?: head)
        if (head.startsWith("CON ") || head.startsWith("LIVE") || head.startsWith("PIRS")) {
            if (head.size < STFS_TITLE_ID_OFFSET + 4) return null
            return titleIdString(u32(head, STFS_TITLE_ID_OFFSET))
        }
        for (partition in PARTITION_OFFSETS) {
            fromXdvdfs(source, partition)?.let { return it }
        }
        return null
    }

    private fun fromXdvdfs(source: DiscImage.SeekableSource, partition: Long): String? {
        val vd = read(source, partition + 32 * SECTOR, 28) ?: return null
        if (vd.size < 28 || !vd.startsWith(XDVDFS_MAGIC)) return null
        val rootSector = le32(vd, 20)
        val rootSize = le32(vd, 24)
        if (rootSector <= 0 || rootSize !in 1..MAX_DIR_SIZE) return null
        val dir = read(source, partition + rootSector * SECTOR, rootSize) ?: return null
        val xex = findEntry(dir, "default.xex") ?: return null
        val header = read(source, partition + xex.first * SECTOR, minOf(xex.second, XEX_HEADER_READ)) ?: return null
        return fromXex(header)
    }

    /**
     * Walks a directory table linearly: each entry is `u16 left, u16 right, u32 sector, u32 size,
     * u8 attributes, u8 name length, name`, padded to 4 bytes; `0xFF` fills unused space and every
     * sector's tail. Returns (sector, size) of [name], matched case-insensitively.
     */
    private fun findEntry(dir: ByteArray, name: String): Pair<Long, Int>? {
        var at = 0
        while (at + 14 <= dir.size) {
            if (dir[at] == 0xFF.toByte() && dir[at + 1] == 0xFF.toByte()) {
                at = ((at / SECTOR.toInt()) + 1) * SECTOR.toInt()   // padding: next sector
                continue
            }
            val sector = le32(dir, at + 4).toLong() and 0xFFFFFFFFL
            val size = le32(dir, at + 8)
            val nameLen = dir[at + 13].toInt() and 0xFF
            if (nameLen == 0 || at + 14 + nameLen > dir.size) return null
            val entryName = String(dir, at + 14, nameLen, Charsets.ISO_8859_1)
            if (entryName.equals(name, ignoreCase = true)) return sector to size
            at = (at + 14 + nameLen + 3) and 3.inv()
        }
        return null
    }

    // Title ids are u32s; 0 and all-ones mean "none".
    private fun titleIdString(id: Int): String? =
        if (id == 0 || id == -1) null else "%08X".format(id)

    private fun read(source: DiscImage.SeekableSource, offset: Long, len: Int): ByteArray? {
        val buf = ByteArray(len)
        val n = runCatching { source.readFully(offset, buf, len) }.getOrDefault(-1)
        return if (n <= 0) null else if (n == len) buf else buf.copyOf(n)
    }

    private fun ByteArray.startsWith(text: String): Boolean =
        size >= text.length && text.indices.all { this[it] == text[it].code.toByte() }

    private fun u32(b: ByteArray, o: Int): Int =
        ((b[o].toInt() and 0xFF) shl 24) or ((b[o + 1].toInt() and 0xFF) shl 16) or
            ((b[o + 2].toInt() and 0xFF) shl 8) or (b[o + 3].toInt() and 0xFF)

    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)
}
