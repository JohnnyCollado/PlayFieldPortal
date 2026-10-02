package com.playfieldportal.feature.xmb.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.playfieldportal.core.domain.playlist.PlaylistFileParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Reads picked document URIs into [PickedPlaylistFile]s: the display name from
 * [OpenableColumns.DISPLAY_NAME], the bytes capped at [PlaylistFileParser.MAX_BYTES]. A file that
 * cannot be read becomes an `Unreadable` so one bad pick never stops the batch. Checked on the
 * device; it needs a ContentResolver.
 */
class PlaylistDocumentReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun read(uris: List<Uri>): List<PickedPlaylistFile> = withContext(Dispatchers.IO) {
        uris.map { uri -> readOne(uri) }
    }

    private fun readOne(uri: Uri): PickedPlaylistFile {
        val name = displayName(uri)
        return try {
            val stream = context.contentResolver.openInputStream(uri)
                ?: return PickedPlaylistFile.Unreadable(name, "Couldn't open the file")
            val bytes = stream.use { it.readNBytes(PlaylistFileParser.MAX_BYTES + 1) }
            if (bytes.size > PlaylistFileParser.MAX_BYTES) {
                PickedPlaylistFile.Unreadable(name, "File is larger than ${PlaylistFileParser.MAX_BYTES / (1024 * 1024)} MiB")
            } else {
                PickedPlaylistFile.Content(name, bytes)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Couldn't read playlist file $uri")
            PickedPlaylistFile.Unreadable(name, e.message ?: "Couldn't read the file")
        }
    }

    private fun displayName(uri: Uri): String {
        val queried = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        return queried?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "playlist"
    }
}
