package com.playfieldportal.feature.achievements.match

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * A seekable CD/DVD image reader plus the slice of ISO9660 that RetroAchievements' disc hashing
 * needs: locate a file by path and read its sectors. Never loads the whole (multi-GB) image — it
 * seeks to the sectors it needs. Supports cooked 2048-byte images (.iso) and raw 2352-byte images
 * (.bin, Mode 1 or Mode 2), detected from the ISO9660 "CD001" signature at logical sector 16.
 *
 * Transcribed from rcheevos rc_cd_find_file_sector / rc_hash_cd_file (src/rhash/hash_disc.c), so a
 * file's content hash matches RA's. See docs/shiba-coins-achievements-plan.md.
 */
class DiscImage private constructor(
    private val sectors: SectorSource,
    /**
     * The first logical sector of the track the ISO9660 filesystem lives on. Zero for a single-track
     * image (the PVD sits at sector 16); non-zero for a GD-ROM where the data track starts deep into
     * the disc (Dreamcast track 3 at LBA 45000), so the PVD is at [firstTrackSector] + 16.
     */
    val firstTrackSector: Int,
) : Closeable {

    /** A located directory entry: its starting logical sector and byte length. */
    data class Entry(val lba: Int, val size: Int)

    /** Reads up to [count] (<= 2048) bytes of user data from logical sector [lba]. */
    fun readSector(lba: Int, count: Int): ByteArray = sectors.readSector(lba, count)

    /**
     * Finds a file (or directory) by ISO9660 path. Backslash-separated; each parent is resolved
     * first, then the leaf searched within it. Case-insensitive, and matches names with or without
     * the trailing ";1" version — mirroring rc_cd_find_file_sector exactly.
     */
    fun findFile(pathIn: String): Entry? {
        var path = pathIn.trimStart('\\')
        val slash = path.lastIndexOf('\\')

        var sector: Int
        var dirSectors: Int
        if (slash >= 0) {
            val parent = findFile(path.substring(0, slash)) ?: return null
            path = path.substring(slash + 1)
            sector = parent.lba
            dirSectors = 1 // like rcheevos, only the first sector of a subdirectory is searched
        } else {
            val pvd = readSector(firstTrackSector + 16, 256)
            if (pvd.size < 170) return null
            sector = u24(pvd, 156 + 2)
            val blockSize = pvd[128].u() or (pvd[129].u() shl 8)
            dirSectors = if (blockSize == 0) 1 else u32(pvd, 156 + 10) / blockSize
        }

        val nameLen = path.length
        var buf = readSector(sector, 2048)
        var i = 0
        while (true) {
            if (i >= buf.size || buf[i].toInt() == 0) {
                if (dirSectors > 1) {
                    dirSectors--
                    buf = readSector(++sector, 2048)
                    if (buf.isEmpty()) break
                    i = 0
                    continue
                }
                break
            }
            val recLen = buf[i].u()
            val idLen = buf[i + 32].u()
            val afterName = i + 33 + nameLen
            val boundaryOk = idLen == nameLen || (afterName < buf.size && buf[afterName].toInt().toChar() == ';')
            if (boundaryOk && i + 33 + nameLen <= buf.size && regionMatchesCi(buf, i + 33, path)) {
                return Entry(lba = u24(buf, i + 2), size = u32(buf, i + 10))
            }
            if (recLen == 0) break
            i += recLen
        }
        return null
    }

    /** Appends [size] bytes of the file starting at [lba] to [md5], 2048 bytes per sector. */
    fun hashFileInto(md5: MessageDigest, lba: Int, size: Int) {
        var remaining = size
        var sector = lba
        while (remaining > 0) {
            val want = minOf(remaining, 2048)
            val chunk = readSector(sector, want)
            if (chunk.isEmpty()) break
            md5.update(chunk, 0, chunk.size)
            remaining -= chunk.size
            if (chunk.size < want) break
            sector++
        }
    }

    override fun close() = sectors.close()

    // ── helpers ────────────────────────────────────────────────────────────────
    private fun Byte.u(): Int = toInt() and 0xFF
    private fun u24(b: ByteArray, o: Int) = b[o].u() or (b[o + 1].u() shl 8) or (b[o + 2].u() shl 16)
    private fun u32(b: ByteArray, o: Int) =
        b[o].u() or (b[o + 1].u() shl 8) or (b[o + 2].u() shl 16) or (b[o + 3].u() shl 24)

    private fun regionMatchesCi(b: ByteArray, off: Int, s: String): Boolean {
        for (k in s.indices) {
            if ((b[off + k].toInt().toChar()).uppercaseChar() != s[k].uppercaseChar()) return false
        }
        return true
    }

    /** A random-access byte source (a file or a content-provider fd). */
    interface SeekableSource : Closeable {
        /** Reads exactly [len] bytes at [offset] into [dest], or fewer at EOF; returns bytes read. */
        fun readFully(offset: Long, dest: ByteArray, len: Int): Int
    }

    /**
     * Maps a logical sector to its user-data bytes. A single-file image is one linear track; a
     * GD-ROM (Dreamcast GDI) spans several track files with their own base LBA and layout, so the
     * lookup is by absolute sector (see [GdiTrackSource]).
     */
    interface SectorSource : Closeable {
        /** Reads up to [count] (<= 2048) bytes of user data from logical sector [lba]. */
        fun readSector(lba: Int, count: Int): ByteArray
    }

    // One continuous track: sector N's user data is at N * sectorSize + dataOffset.
    private class LinearSectors(
        private val source: SeekableSource,
        private val sectorSize: Int,
        private val dataOffset: Int,
    ) : SectorSource {
        override fun readSector(lba: Int, count: Int): ByteArray {
            val out = ByteArray(count)
            val read = source.readFully(lba.toLong() * sectorSize + dataOffset, out, count)
            return if (read == count) out else out.copyOf(maxOf(read, 0))
        }
        override fun close() = source.close()
    }

    private class FileSource(private val raf: RandomAccessFile) : SeekableSource {
        override fun readFully(offset: Long, dest: ByteArray, len: Int): Int {
            raf.seek(offset)
            var total = 0
            while (total < len) {
                val n = raf.read(dest, total, len - total)
                if (n < 0) break
                total += n
            }
            return total
        }
        override fun close() = raf.close()
    }

    companion object {
        /** A raw seekable reader over [file], for hashers that read absolute byte offsets (GC/Wii). */
        fun rawSource(file: File): SeekableSource = FileSource(RandomAccessFile(file, "r"))

        /** Opens an image file, detecting its sector layout, or null if it isn't an ISO9660 disc. */
        fun open(file: File): DiscImage? =
            open(FileSource(RandomAccessFile(file, "r")))

        /** Opens from any seekable source (e.g. a content-provider fd wrapper). */
        fun open(source: SeekableSource): DiscImage? {
            val layout = detectLayout(source)
            if (layout == null) { source.close(); return null }
            return DiscImage(LinearSectors(source, layout.first, layout.second), firstTrackSector = 0)
        }

        /**
         * Opens a CD image whose filesystem is NOT ISO9660 (Sega CD / Saturn / Dreamcast), detecting
         * the physical sector layout from the raw-sector sync pattern instead of a "CD001" descriptor.
         * The caller validates the disc's own header (e.g. the SEGA magic) after opening.
         */
        fun openRawCd(source: SeekableSource): DiscImage {
            val layout = detectRawLayout(source)
            return DiscImage(LinearSectors(source, layout.first, layout.second), firstTrackSector = 0)
        }

        /**
         * Opens a multi-track disc (a Dreamcast GDI) whose ISO9660 filesystem lives on the track that
         * starts at [firstTrackSector]. Sector reads route to the owning track via [sectors].
         */
        fun openTracks(sectors: SectorSource, firstTrackSector: Int): DiscImage =
            DiscImage(sectors, firstTrackSector)

        // A raw 2352-byte sector opens with the 12-byte sync 00 FF*10 00; the mode byte at offset 15
        // then selects the user-data offset (16 for Mode 1, 24 for Mode 2). No sync => cooked 2048.
        private fun detectRawLayout(source: SeekableSource): Pair<Int, Int> {
            val head = ByteArray(16)
            if (source.readFully(0, head, 16) < 16) return 2048 to 0
            val isRaw = head[0].toInt() == 0 && head[11].toInt() == 0 &&
                (1..10).all { head[it].toInt() and 0xFF == 0xFF }
            if (!isRaw) return 2048 to 0
            return if ((head[15].toInt() and 0xFF) == 2) 2352 to 24 else 2352 to 16
        }

        // The ISO9660 primary volume descriptor sits at logical sector 16 with "CD001" one byte in.
        // Probe the three common physical layouts to identify sector size + user-data offset.
        private fun detectLayout(source: SeekableSource): Pair<Int, Int>? {
            fun cd001At(offset: Long): Boolean {
                val b = ByteArray(5)
                return source.readFully(offset, b, 5) == 5 &&
                    b[0].toInt() == 'C'.code && b[1].toInt() == 'D'.code && b[2].toInt() == '0'.code &&
                    b[3].toInt() == '0'.code && b[4].toInt() == '1'.code
            }
            return when {
                cd001At(16L * 2048 + 1) -> 2048 to 0        // cooked (.iso)
                cd001At(16L * 2352 + 16 + 1) -> 2352 to 16  // raw Mode 1 (.bin)
                cd001At(16L * 2352 + 24 + 1) -> 2352 to 24  // raw Mode 2 (.bin)
                else -> null
            }
        }
    }
}
