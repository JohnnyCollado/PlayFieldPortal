package com.playfieldportal.feature.achievements.match

import android.content.Context
import android.net.Uri
import com.playfieldportal.core.domain.model.Game
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads a game's ROM bytes for hashing, from a raw path or a SAF content URI. Zipped ROMs are
 * unwrapped to their single inner file (RA hashes the ROM, not the archive). Reads above
 * [MAX_BYTES] are skipped — cartridge ROMs fit comfortably; disc images never reach here.
 */
@Singleton
class RomBytesReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun read(game: Game): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val identifier = game.romPath ?: game.romUri ?: return@runCatching null
            val isZip = identifier.endsWith(".zip", ignoreCase = true)
            openStream(game)?.use { stream ->
                if (isZip) readFirstZipEntry(stream) else capped(stream.readBytes())
            }
        }.getOrNull()
    }

    // Prefer a real raw-path file, but fall back to the SAF URI: romPath is often a SAF-derived
    // (non-filesystem) string, so a failed File check must not stop us from opening the content URI.
    private fun openStream(game: Game): InputStream? {
        game.romPath?.let { File(it).takeIf(File::exists) }?.let { return it.inputStream() }
        game.romUri?.let { return context.contentResolver.openInputStream(Uri.parse(it)) }
        return null
    }

    private fun readFirstZipEntry(stream: InputStream): ByteArray? {
        ZipInputStream(stream).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) return capped(zip.readBytes())
                entry = zip.nextEntry
            }
        }
        return null
    }

    private fun capped(bytes: ByteArray): ByteArray? = bytes.takeIf { it.size <= MAX_BYTES }

    private companion object {
        // Large enough for Nintendo DS carts (up to ~256 MB); disc images stay well above this.
        const val MAX_BYTES = 256 * 1024 * 1024
    }
}
