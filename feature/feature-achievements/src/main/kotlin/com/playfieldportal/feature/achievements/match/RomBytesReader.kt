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

    private fun openStream(game: Game): InputStream? = when {
        game.romPath != null -> File(game.romPath).takeIf(File::exists)?.inputStream()
        game.romUri != null -> context.contentResolver.openInputStream(Uri.parse(game.romUri))
        else -> null
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
        const val MAX_BYTES = 64 * 1024 * 1024
    }
}
