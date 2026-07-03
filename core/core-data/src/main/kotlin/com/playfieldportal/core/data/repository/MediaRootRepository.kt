package com.playfieldportal.core.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
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

/** Which media section a root folder belongs to (each has exactly one root). */
enum class MediaRootKind(internal val key: String) {
    MUSIC("music_root_tree_uris"),
    VIDEO("video_root_tree_uris"),
    PHOTO("photo_root_tree_uris"),
}

/**
 * The single ROOT folder for each media section. One persisted SAF tree grant; its subfolders become
 * the libraries (auto-managed on scan). Stored under the existing keys, so a previous multi-root
 * value upgrades cleanly — the first entry is kept. Adding a root when one exists replaces it.
 */
@Singleton
class MediaRootRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun observe(kind: MediaRootKind): Flow<String?> =
        context.pfpDataStore.data.map { it[stringPreferencesKey(kind.key)]?.let(::firstUri) }

    suspend fun get(kind: MediaRootKind): String? =
        context.pfpDataStore.data.first()[stringPreferencesKey(kind.key)]?.let(::firstUri)

    /** Sets (replaces) the single root for [kind]. */
    suspend fun set(kind: MediaRootKind, treeUri: String) {
        context.pfpDataStore.edit { it[stringPreferencesKey(kind.key)] = treeUri }
        Timber.i("${kind.name} root set: $treeUri")
    }

    suspend fun clear(kind: MediaRootKind) {
        context.pfpDataStore.edit { it.remove(stringPreferencesKey(kind.key)) }
    }

    /** Takes a persistable read grant on the picked tree. Safe to call repeatedly. */
    fun persist(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { Timber.w(it, "Could not persist media root permission for $uri") }
    }

    // Stored value may be a legacy newline-joined list — the single root is the first non-blank entry.
    private fun firstUri(stored: String): String? =
        stored.split('\n').map { it.trim() }.firstOrNull { it.isNotEmpty() }
}
