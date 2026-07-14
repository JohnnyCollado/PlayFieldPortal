package com.playfieldportal.feature.achievements.match

import com.playfieldportal.feature.achievements.match.DiscImage.SeekableSource

/**
 * A Dreamcast GD-ROM (GDI dump) exposed as one logical disc: absolute logical sectors are routed to
 * the track file that owns them. GD-ROM data lives high on the disc (the data track typically starts
 * at LBA 45000) and a title is split across several track files, so the ISO9660 filesystem addresses
 * files by absolute LBA — [DiscImage] reads through this router with the data track's start as its
 * base. Each track carries its base LBA, physical sector size (2048 cooked or 2352 raw), the
 * user-data offset within a sector, and a byte offset into its file. Never loads a whole track.
 */
class GdiTrackSource(private val tracks: List<Track>) : DiscImage.SectorSource {

    /** One GDI track: [number] and [type] (4 = data, 0 = audio) come straight from the `.gdi` index. */
    class Track(
        val number: Int,
        val type: Int,
        val startLba: Int,
        val sectorCount: Int,
        val sectorSize: Int,
        val dataOffset: Int,
        val fileOffset: Long,
        val source: SeekableSource,
    )

    override fun readSector(lba: Int, count: Int): ByteArray {
        val t = tracks.firstOrNull { lba >= it.startLba && lba < it.startLba + it.sectorCount }
            ?: return ByteArray(0)
        val byte = t.fileOffset + (lba - t.startLba).toLong() * t.sectorSize + t.dataOffset
        val out = ByteArray(count)
        val read = t.source.readFully(byte, out, count)
        return if (read == count) out else out.copyOf(maxOf(read, 0))
    }

    /**
     * The start LBA of the track carrying IP.BIN (and the ISO9660 filesystem): rcheevos uses track 3,
     * falling back to the first data track for MIL-CD layouts. Returns null if neither has the
     * Dreamcast boot header.
     */
    fun ipBinTrackStart(): Int? {
        tracks.firstOrNull { it.number == 3 }?.let { if (hasKatanaHeader(it.startLba)) return it.startLba }
        tracks.filter { it.type == 4 }.minByOrNull { it.startLba }
            ?.let { if (hasKatanaHeader(it.startLba)) return it.startLba }
        return null
    }

    private fun hasKatanaHeader(startLba: Int): Boolean =
        RaDreamcastHasher.isDreamcastHeader(readSector(startLba, 16))

    override fun close() = tracks.forEach { runCatching { it.source.close() } }
}
