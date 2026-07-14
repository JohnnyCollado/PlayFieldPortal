package com.playfieldportal.feature.achievements.match

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.playfieldportal.core.domain.model.Game
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens a game's disc image as a seekable [DiscImage] for hashing, from a raw filesystem path or a
 * SAF content URI. A `.cue` sheet is followed to its first `FILE` (the `.bin`); a direct `.iso` /
 * `.bin` / `.img` is opened as-is. Never reads the whole (multi-GB) image.
 */
@Singleton
class DiscImageOpener @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun open(game: Game): DiscImage? = withContext(Dispatchers.IO) {
        runCatching { openFromPath(game) ?: openFromUri(game) }
            .onFailure { Timber.w(it, "disc open failed for %s", game.displayTitle) }
            .getOrNull()
    }

    /**
     * A raw seekable reader over a game's disc image, for the Nintendo optical hashers that read
     * absolute byte offsets (GameCube / Wii) rather than ISO9660 sectors. These are single-file
     * images, so no `.cue` following is needed. Never reads the whole (multi-GB) image.
     */
    suspend fun openRawSource(game: Game): DiscImage.SeekableSource? = withContext(Dispatchers.IO) {
        runCatching { rawFromPath(game) ?: rawFromUri(game) }
            .onFailure { Timber.w(it, "raw disc open failed for %s", game.displayTitle) }
            .getOrNull()
    }

    private fun rawFromPath(game: Game): DiscImage.SeekableSource? {
        val path = game.romPath ?: return null
        val file = File(path).takeIf(File::exists) ?: return null
        return DiscImage.rawSource(file)
    }

    private fun rawFromUri(game: Game): DiscImage.SeekableSource? {
        val uri = game.romUri ?: return null
        val pfd = context.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: return null
        return FdSource(pfd)
    }

    /**
     * Opens a game's disc image as a non-ISO9660 CD (Sega CD / Saturn), detecting the sector layout
     * from the raw sync pattern. Follows a `.cue` to its `.bin` like [open]; the caller validates the
     * disc header. Never reads the whole image.
     */
    suspend fun openRawCd(game: Game): DiscImage? = withContext(Dispatchers.IO) {
        runCatching { rawCdFromPath(game) ?: rawCdFromUri(game) }
            .onFailure { Timber.w(it, "raw CD open failed for %s", game.displayTitle) }
            .getOrNull()
    }

    private fun rawCdFromPath(game: Game): DiscImage? {
        val path = game.romPath ?: return null
        val file = File(path).takeIf(File::exists) ?: return null
        val data = if (path.endsWith(".cue", ignoreCase = true)) cueBinFile(file) else file
        return data?.let { DiscImage.openRawCd(DiscImage.rawSource(it)) }
    }

    private fun rawCdFromUri(game: Game): DiscImage? {
        val uri = game.romUri ?: return null
        if (uri.endsWith(".cue", ignoreCase = true)) return null
        val pfd = context.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: return null
        return DiscImage.openRawCd(FdSource(pfd))
    }

    /**
     * Opens a `.chd` (MAME CHD) CD image as a [DiscImage] of logical sectors. The CHD's hunks
     * decompress to the exact sectors of the uncompressed disc, so the normal PSX/PS2/Sega hashers
     * read it unchanged. Single-file, so both a filesystem path and a SAF URI work; never reads a
     * whole hunk stream. Returns null for non-CD CHDs or codecs we can't decode (LZMA/FLAC-only).
     */
    suspend fun openChd(game: Game): DiscImage? = withContext(Dispatchers.IO) {
        runCatching {
            val source = chdSource(game) ?: return@runCatching null
            val chd = ChdReader.open(source) ?: return@runCatching null // open() closes source on failure
            val sectors = ChdSectorSource.of(chd) ?: run { chd.close(); return@runCatching null }
            DiscImage.openTracks(sectors, sectors.firstTrackSector) // 0 for CD, ~45000 for GD-ROM
        }.onFailure { Timber.w(it, "chd open failed for %s", game.displayTitle) }.getOrNull()
    }

    private fun chdSource(game: Game): DiscImage.SeekableSource? {
        game.romPath?.takeIf { it.endsWith(".chd", ignoreCase = true) }?.let { path ->
            return File(path).takeIf(File::exists)?.let { DiscImage.rawSource(it) }
        }
        game.romUri?.takeIf { it.endsWith(".chd", ignoreCase = true) }?.let { uri ->
            return context.contentResolver.openFileDescriptor(Uri.parse(uri), "r")?.let { FdSource(it) }
        }
        return null
    }

    // Prefer a real filesystem path — it lets us follow a .cue to its sibling .bin.
    private fun openFromPath(game: Game): DiscImage? {
        val path = game.romPath ?: return null
        val file = File(path).takeIf(File::exists) ?: return null
        val data = if (path.endsWith(".cue", ignoreCase = true)) cueBinFile(file) else file
        return data?.let { DiscImage.open(it) }
    }

    // Reads the first `FILE "name" BINARY` from a cue sheet and resolves it in the same directory.
    private fun cueBinFile(cue: File): File? {
        val line = cue.useLines { lines ->
            lines.map { it.trim() }.firstOrNull { it.startsWith("FILE", ignoreCase = true) }
        } ?: return null
        val name = Regex("""FILE\s+"([^"]+)"""", RegexOption.IGNORE_CASE).find(line)?.groupValues?.get(1)
            ?: line.removePrefix("FILE").trim().substringBefore(" BINARY").trim().trim('"')
        if (!isSafeSiblingName(name)) return null
        return File(cue.parentFile, name).takeIf(File::exists)
    }

    // A referenced track/bin file must be a bare sibling name — no path component may escape the
    // .cue/.gdi directory (path-traversal guard for untrusted sheet contents).
    private fun isSafeSiblingName(name: String): Boolean =
        name.isNotEmpty() && !name.contains('/') && !name.contains('\\') && File(name).name == name

    // SAF fallback: open the content URI directly (a bare .iso / .bin; a .cue's sibling can't be
    // resolved through SAF, so those fall to the title match).
    private fun openFromUri(game: Game): DiscImage? {
        val uri = game.romUri ?: return null
        if (uri.endsWith(".cue", ignoreCase = true)) return null
        val pfd = context.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: return null
        return DiscImage.open(FdSource(pfd))
    }

    /**
     * Opens a Dreamcast GD-ROM from its `.gdi` index: parses the track list, opens each track file as
     * a seekable reader, and routes absolute logical sectors through [GdiTrackSource], anchored to the
     * IP.BIN data track. Filesystem-only — the sibling track files can't be resolved through SAF, so a
     * SAF `.gdi` falls to the unmatched path. Never reads a whole track.
     */
    suspend fun openGdi(game: Game): DiscImage? = withContext(Dispatchers.IO) {
        runCatching { openGdiFromPath(game) }
            .onFailure { Timber.w(it, "gdi open failed for %s", game.displayTitle) }
            .getOrNull()
    }

    private fun openGdiFromPath(game: Game): DiscImage? {
        val path = game.romPath ?: return null
        if (!path.endsWith(".gdi", ignoreCase = true)) return null
        val gdi = File(path).takeIf(File::exists) ?: return null
        val dir = gdi.parentFile ?: return null

        val lines = gdi.readLines().map { it.trim() }.filter { it.isNotEmpty() }
        val count = lines.firstOrNull()?.toIntOrNull() ?: return null

        val opened = mutableListOf<DiscImage.SeekableSource>()
        try {
            val tracks = mutableListOf<GdiTrackSource.Track>()
            for (line in lines.drop(1).take(count)) {
                val m = GDI_LINE.find(line) ?: return closeAll(opened)
                val sectorSize = m.groupValues[4].toInt()
                val name = m.groupValues[5].ifEmpty { m.groupValues[6] }
                // Track files are siblings of the .gdi; reject any path component (traversal guard).
                if (!isSafeSiblingName(name)) return closeAll(opened)
                val fileOffset = m.groupValues[7].toLong()
                val file = File(dir, name).takeIf(File::exists) ?: return closeAll(opened)
                val src = DiscImage.rawSource(file).also { opened += it }
                val dataOffset = if (sectorSize == 2048) 0 else rawSectorDataOffset(src, fileOffset)
                val sectorCount = if (sectorSize <= 0) 0 else ((file.length() - fileOffset) / sectorSize).toInt()
                tracks += GdiTrackSource.Track(
                    number = m.groupValues[1].toInt(),
                    type = m.groupValues[3].toInt(),
                    startLba = m.groupValues[2].toInt(),
                    sectorCount = sectorCount,
                    sectorSize = sectorSize,
                    dataOffset = dataOffset,
                    fileOffset = fileOffset,
                    source = src,
                )
            }
            if (tracks.isEmpty()) return closeAll(opened)
            val source = GdiTrackSource(tracks)
            val ipStart = source.ipBinTrackStart() ?: run { source.close(); return null }
            return DiscImage.openTracks(source, ipStart)
        } catch (t: Throwable) {
            opened.forEach { runCatching { it.close() } }
            throw t
        }
    }

    private fun closeAll(sources: List<DiscImage.SeekableSource>): DiscImage? {
        sources.forEach { runCatching { it.close() } }
        return null
    }

    // A GDI data track can be cooked (2048, offset 0) or raw (2352). For raw, the mode byte after the
    // 12-byte sync selects the user-data offset (24 for Mode 2, else 16).
    private fun rawSectorDataOffset(src: DiscImage.SeekableSource, fileOffset: Long): Int {
        val head = ByteArray(16)
        if (src.readFully(fileOffset, head, 16) < 16) return 16
        val isRaw = head[0].toInt() == 0 && head[11].toInt() == 0 &&
            (1..10).all { head[it].toInt() and 0xFF == 0xFF }
        if (!isRaw) return 0
        return if ((head[15].toInt() and 0xFF) == 2) 24 else 16
    }

    // A content-provider fd wrapped as a positioned reader (absolute reads leave the channel alone).
    private class FdSource(private val pfd: ParcelFileDescriptor) : DiscImage.SeekableSource {
        private val channel = FileInputStream(pfd.fileDescriptor).channel
        override fun readFully(offset: Long, dest: ByteArray, len: Int): Int {
            val bb = ByteBuffer.wrap(dest, 0, len)
            var total = 0
            while (total < len) {
                val n = channel.read(bb, offset + total)
                if (n < 0) break
                total += n
            }
            return total
        }
        override fun close() {
            runCatching { channel.close() }
            runCatching { pfd.close() }
        }
    }

    private companion object {
        // A .gdi track line: index, start LBA, type, sector size, filename (quoted or bare), byte offset.
        val GDI_LINE = Regex("""^(\d+)\s+(\d+)\s+(\d+)\s+(\d+)\s+(?:"([^"]+)"|(\S+))\s+(\d+)$""")
    }
}
