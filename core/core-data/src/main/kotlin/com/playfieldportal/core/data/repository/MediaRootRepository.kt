package com.playfieldportal.core.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** Which media section a root folder belongs to. */
enum class MediaRootKind(internal val key: String) {
    MUSIC("music_root_tree_uris"),
    VIDEO("video_root_tree_uris"),
    PHOTO("photo_root_tree_uris"),
}

/**
 * The user's ROOT folders for each media section (Music / Video / Photo). Each root is a persisted
 * `ACTION_OPEN_DOCUMENT_TREE` grant; its subfolders become that section's libraries (auto-managed
 * on scan). Multiple roots let a section span internal storage and an SD card.
 *
 * Mirrors [RomRootRepository]: roots are stored newline-joined under the SAME per-kind key the
 * legacy single-root writes used, so older installs and backups migrate transparently — a stored
 * single value reads back as a one-entry list, and the legacy multi-root shape (a newline-joined
 * value whose first entry was picked) is honored by taking every entry.
 */
@Singleton
class MediaRootRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** All configured roots for [kind], in the order they were added. */
    fun roots(kind: MediaRootKind): Flow<List<String>> =
        context.pfpDataStore.data.map { readRoots(it, kind) }

    suspend fun getAll(kind: MediaRootKind): List<String> =
        readRoots(context.pfpDataStore.data.first(), kind)

    /** Adds a root for [kind] (deduplicated, order-preserving). No-op for a blank URI. */
    suspend fun add(kind: MediaRootKind, treeUri: String) {
        if (treeUri.isBlank()) return
        val next = LinkedHashSet(getAll(kind)).apply { add(treeUri) }.toList()
        writeRoots(kind, next)
        Timber.i("%s root added: %s (total %d)", kind.name, treeUri, next.size)
    }

    suspend fun remove(kind: MediaRootKind, treeUri: String) {
        val next = getAll(kind).filterNot { it == treeUri }
        writeRoots(kind, next)
        Timber.i("%s root removed: %s (total %d)", kind.name, treeUri, next.size)
    }

    /** Replaces one root URI with another (used when a re-link picks a different folder). */
    suspend fun replace(kind: MediaRootKind, oldTreeUri: String, newTreeUri: String) {
        if (newTreeUri.isBlank()) return
        val current = getAll(kind)
        val next = if (oldTreeUri in current) {
            LinkedHashSet(current.map { if (it == oldTreeUri) newTreeUri else it })
        } else {
            LinkedHashSet(current).apply { add(newTreeUri) }
        }
        writeRoots(kind, next.toList())
    }

    /** Takes a persistable read grant on the picked tree. Safe to call repeatedly. */
    fun persist(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { Timber.w(it, "Could not persist media root permission for %s", uri) }
    }

    /** Removes every root for [kind] (used by the backup-restore reset paths). */
    suspend fun clear(kind: MediaRootKind) {
        writeRoots(kind, emptyList())
    }

    private fun readRoots(prefs: Preferences, kind: MediaRootKind): List<String> =
        prefs[stringPreferencesKey(kind.key)]
            ?.split('\n')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    private suspend fun writeRoots(kind: MediaRootKind, roots: List<String>) {
        context.pfpDataStore.edit { prefs ->
            if (roots.isEmpty()) prefs.remove(stringPreferencesKey(kind.key))
            else prefs[stringPreferencesKey(kind.key)] = roots.joinToString("\n")
        }
    }
}
