package com.playfieldportal.core.data.repository

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * Deletes cached thumbnail files whose library rows have just been removed, so removing content
 * from PFP also forgets its cached imagery (privacy: no downscaled copies linger in app storage).
 *
 * [stillReferenced] re-checks the DB per uri AFTER the rows are gone; a thumbnail still used by a
 * surviving row (the same source file added through two libraries shares one cache file) is kept.
 * Only app-internal file:// uris are touched — the user's original media is never deleted.
 */
internal suspend fun deleteOrphanedThumbnails(
    thumbnailUris: Collection<String>,
    stillReferenced: suspend (String) -> Boolean,
) {
    if (thumbnailUris.isEmpty()) return
    withContext(Dispatchers.IO) {
        var deleted = 0
        for (uriStr in thumbnailUris.distinct()) {
            runCatching {
                val uri = Uri.parse(uriStr)
                if (uri.scheme != "file") return@runCatching
                if (stillReferenced(uriStr)) return@runCatching
                val path = uri.path ?: return@runCatching
                if (File(path).delete()) deleted++
            }.onFailure { Timber.w(it, "Could not delete cached thumbnail") }
        }
        if (deleted > 0) Timber.i("Deleted $deleted orphaned cached thumbnail(s)")
    }
}
