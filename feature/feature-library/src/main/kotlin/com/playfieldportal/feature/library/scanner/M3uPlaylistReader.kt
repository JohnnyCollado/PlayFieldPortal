package com.playfieldportal.feature.library.scanner

import android.content.Context
import android.net.Uri
import com.playfieldportal.core.domain.model.Game
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Reads an `.m3u` playlist's raw entry lines for [DiscSetBuilder]. Raw-path games are read from
 * disk; SAF games from their document URI (the derived raw path may not exist as a File under
 * scoped storage). Shared by [RomScanner] (fresh-scan enrichment) and [LibraryScanner]
 * (incremental reconciliation over existing rows) so both read playlists identically. An
 * unreadable playlist is a soft failure — the discs keep their own identity and the `.m3u`
 * simply stays a plain game row.
 */
@Singleton
class M3uPlaylistReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun read(game: Game): List<String>? {
        val path = game.romPath ?: return null
        if (!path.endsWith(".m3u", ignoreCase = true)) return null
        return try {
            if (!game.romUri.isNullOrBlank()) {
                context.contentResolver.openInputStream(Uri.parse(game.romUri))
                    ?.bufferedReader()?.readLines()
            } else {
                File(path).takeIf { it.isFile }?.readLines()
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not read playlist $path — discs keep their own identity")
            null
        }
    }
}
