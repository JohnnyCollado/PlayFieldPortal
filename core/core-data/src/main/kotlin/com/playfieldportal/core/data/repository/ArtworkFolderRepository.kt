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

// Persisted SAF root of the user-owned artwork library. Backed up (inert without a live grant) so
// a restore can pre-point the re-link picker at the old location.
private val KEY_ARTWORK_FOLDER_TREE_URI = stringPreferencesKey("artwork_folder_tree_uri")

// "internal" (filesDir, the default) or "portable" (the SAF tree above). Flipped only after the
// portable library is usable, never mid-operation.
private val KEY_ARTWORK_STORAGE_MODE = stringPreferencesKey("artwork_storage_mode")

// UUID minted when a library manifest is first written; distinguishes "same library re-linked"
// from "a different library" when a picked folder already contains a manifest.
private val KEY_ARTWORK_LIBRARY_UUID = stringPreferencesKey("artwork_library_uuid")

enum class ArtworkStorageMode { INTERNAL, PORTABLE;
    companion object {
        fun fromName(name: String?): ArtworkStorageMode =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: INTERNAL
    }
}

/**
 * The user-chosen artwork library root, held as a persisted `ACTION_OPEN_DOCUMENT_TREE` grant —
 * the same SAF pattern as [BackupFolderRepository]: no storage permission, survives uninstall,
 * stays user-accessible, never a raw path. The grant is read+write (PFP writes artwork into it)
 * and is the single grant covering both the library (`games/…`) and the import drop zone
 * (`import/<Launcher>/…`).
 */
@Singleton
class ArtworkFolderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val treeUri: Flow<String?> = context.pfpDataStore.data.map { it[KEY_ARTWORK_FOLDER_TREE_URI] }

    val storageMode: Flow<ArtworkStorageMode> =
        context.pfpDataStore.data.map { ArtworkStorageMode.fromName(it[KEY_ARTWORK_STORAGE_MODE]) }

    suspend fun getTreeUri(): String? =
        context.pfpDataStore.data.first()[KEY_ARTWORK_FOLDER_TREE_URI]

    suspend fun getStorageMode(): ArtworkStorageMode =
        ArtworkStorageMode.fromName(context.pfpDataStore.data.first()[KEY_ARTWORK_STORAGE_MODE])

    suspend fun getLibraryUuid(): String? =
        context.pfpDataStore.data.first()[KEY_ARTWORK_LIBRARY_UUID]

    suspend fun setTreeUri(treeUri: String?) {
        context.pfpDataStore.edit { prefs ->
            if (treeUri.isNullOrBlank()) prefs.remove(KEY_ARTWORK_FOLDER_TREE_URI)
            else prefs[KEY_ARTWORK_FOLDER_TREE_URI] = treeUri
        }
        Timber.i("Artwork folder set: $treeUri")
    }

    suspend fun setStorageMode(mode: ArtworkStorageMode) {
        context.pfpDataStore.edit { it[KEY_ARTWORK_STORAGE_MODE] = mode.name.lowercase() }
        Timber.i("Artwork storage mode: $mode")
    }

    suspend fun setLibraryUuid(uuid: String?) {
        context.pfpDataStore.edit { prefs ->
            if (uuid.isNullOrBlank()) prefs.remove(KEY_ARTWORK_LIBRARY_UUID)
            else prefs[KEY_ARTWORK_LIBRARY_UUID] = uuid
        }
    }

    /** Persists a read+write grant (artwork is written into the tree and read back by the UI). */
    fun persist(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Timber.w(it, "Could not persist artwork folder permission for $uri") }
    }

    /** True when the stored tree still has a live persisted read+write grant. */
    suspend fun hasLiveGrant(): Boolean {
        val stored = getTreeUri() ?: return false
        return context.contentResolver.persistedUriPermissions.any {
            it.uri.toString() == stored && it.isReadPermission && it.isWritePermission
        }
    }

    /**
     * Forgets the folder: releases the persisted grant and clears the stored URI + mode. The
     * folder's contents are never touched — the library stays user-owned on disk.
     */
    suspend fun forget() {
        getTreeUri()?.let { stored ->
            runCatching {
                context.contentResolver.releasePersistableUriPermission(
                    Uri.parse(stored),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.onFailure { Timber.w(it, "Could not release artwork folder permission for $stored") }
        }
        context.pfpDataStore.edit { prefs ->
            prefs.remove(KEY_ARTWORK_FOLDER_TREE_URI)
            prefs.remove(KEY_ARTWORK_STORAGE_MODE)
            prefs.remove(KEY_ARTWORK_LIBRARY_UUID)
        }
        Timber.i("Artwork folder forgotten (grant released, files untouched)")
    }
}
