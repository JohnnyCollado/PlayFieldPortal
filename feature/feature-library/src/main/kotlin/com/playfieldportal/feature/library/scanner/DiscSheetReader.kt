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
 * Reads a `.cue` / `.gdi` game's raw sheet lines for [DiscSetBuilder], which uses them to tell a
 * stored row for a sheet's companion file apart from a real disc. Raw-path games are read from
 * disk; SAF games from their document URI, the same split as [M3uPlaylistReader]. An unreadable
 * sheet is a soft failure — nothing is treated as its companion.
 */
@Singleton
class DiscSheetReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun read(game: Game): List<String>? {
        val path = game.romPath ?: return null
        if (!path.endsWith(".cue", ignoreCase = true) && !path.endsWith(".gdi", ignoreCase = true)) return null
        return try {
            if (!game.romUri.isNullOrBlank()) {
                context.contentResolver.openInputStream(Uri.parse(game.romUri))
                    ?.bufferedReader()?.use { it.readLines() }
            } else {
                File(path).takeIf { it.isFile }?.readLines()
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not read disc sheet $path — its companions stay as they are")
            null
        }
    }
}
