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

// Persisted SAF folder where backups are written. Backed up (inert without a live grant) so a
// restore can pre-point the Folder Access picker at the same location.
private val KEY_BACKUP_FOLDER_TREE_URI = stringPreferencesKey("backup_folder_tree_uri")

/**
 * The user-chosen backup destination, held as a persisted `ACTION_OPEN_DOCUMENT_TREE` grant. Using
 * SAF means backups need no storage permission, survive an uninstall, and stay user-accessible —
 * unlike a raw write to a public folder (which required MANAGE_EXTERNAL_STORAGE).
 */
@Singleton
class BackupFolderRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val treeUri: Flow<String?> = context.pfpDataStore.data.map { it[KEY_BACKUP_FOLDER_TREE_URI] }

    suspend fun get(): String? = context.pfpDataStore.data.first()[KEY_BACKUP_FOLDER_TREE_URI]

    suspend fun set(treeUri: String?) {
        context.pfpDataStore.edit { prefs ->
            if (treeUri.isNullOrBlank()) prefs.remove(KEY_BACKUP_FOLDER_TREE_URI)
            else prefs[KEY_BACKUP_FOLDER_TREE_URI] = treeUri
        }
        Timber.i("Backup folder set: $treeUri")
    }

    /** Persists a read+write grant (backups must be written, listed and read back). */
    fun persist(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }.onFailure { Timber.w(it, "Could not persist backup folder permission for $uri") }
    }
}
