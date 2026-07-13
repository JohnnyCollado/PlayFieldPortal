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
        return File(cue.parentFile, name).takeIf(File::exists)
    }

    // SAF fallback: open the content URI directly (a bare .iso / .bin; a .cue's sibling can't be
    // resolved through SAF, so those fall to the title match).
    private fun openFromUri(game: Game): DiscImage? {
        val uri = game.romUri ?: return null
        if (uri.endsWith(".cue", ignoreCase = true)) return null
        val pfd = context.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: return null
        return DiscImage.open(FdSource(pfd))
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
}
