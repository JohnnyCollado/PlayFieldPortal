package com.playfieldportal.core.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// Persisted SAF root of the user-owned artwork library. Backed up (inert without a live grant) so
// a restore can pre-point the re-link picker at the old location.
private val KEY_ARTWORK_FOLDER_TREE_URI = stringPreferencesKey("artwork_folder_tree_uri")

// UUID minted when a library manifest is first written; distinguishes "same library re-linked"
// from "a different library" when a picked folder already contains a manifest.
private val KEY_ARTWORK_LIBRARY_UUID = stringPreferencesKey("artwork_library_uuid")

// "Not now" / "Later" on the artwork-folder prompts. Device-local session state: not backed up, and
// cleared once the folder is ready again.
private val KEY_ARTWORK_FOLDER_PROMPT_DEFERRED = booleanPreferencesKey("artwork_folder_prompt_deferred")

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
    suspend fun getTreeUri(): String? =
        context.pfpDataStore.data.first()[KEY_ARTWORK_FOLDER_TREE_URI]

    suspend fun getLibraryUuid(): String? =
        context.pfpDataStore.data.first()[KEY_ARTWORK_LIBRARY_UUID]

    suspend fun isPromptDeferred(): Boolean =
        context.pfpDataStore.data.first()[KEY_ARTWORK_FOLDER_PROMPT_DEFERRED] ?: false

    suspend fun setPromptDeferred(deferred: Boolean) {
        context.pfpDataStore.edit { prefs ->
            if (deferred) prefs[KEY_ARTWORK_FOLDER_PROMPT_DEFERRED] = true
            else prefs.remove(KEY_ARTWORK_FOLDER_PROMPT_DEFERRED)
        }
    }

    suspend fun setTreeUri(treeUri: String?) {
        context.pfpDataStore.edit { prefs ->
            if (treeUri.isNullOrBlank()) prefs.remove(KEY_ARTWORK_FOLDER_TREE_URI)
            else prefs[KEY_ARTWORK_FOLDER_TREE_URI] = treeUri
        }
        Timber.i("Artwork folder set: $treeUri")
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
}
