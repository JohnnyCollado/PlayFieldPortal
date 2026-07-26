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

private val KEY_VITA3K_UX0_TREE_URI = stringPreferencesKey("vita3k_ux0_tree_uri")

/**
 * Holds the user's grant to Vita3K's `ux0` folder — the single SAF permission that powers both
 * Vita game discovery/launch (Phase 1) and trophy tracking (Phase 2). Vita3K's `ux0` location is a
 * user-set `pref-path` and is often app-private (`Android/data/…`, unreadable by SAF), so the user
 * points PFP at a shared-storage `ux0` (e.g. `Roms/vita/ux0`) and grants it once here.
 *
 * Mirrors the grant model of the ROM roots: [setUx0Folder] persists the tree permission and stores
 * the URI; every Vita reader resolves child docs from [ux0TreeUri] via tree-scoped SAF queries.
 */
@Singleton
class Vita3KLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** The granted `ux0` tree URI, or null when the user hasn't set one. */
    val ux0TreeUriFlow: Flow<String?> = context.pfpDataStore.data.map { it[KEY_VITA3K_UX0_TREE_URI] }

    /** Snapshot of [ux0TreeUriFlow]. Null when unset — callers short-circuit to empty. */
    suspend fun ux0TreeUri(): String? = context.pfpDataStore.data.first()[KEY_VITA3K_UX0_TREE_URI]

    /** Persists a read grant on [treeUri] and stores it as the Vita3K `ux0` folder. */
    suspend fun setUx0Folder(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { Timber.w(it, "Could not persist Vita3K ux0 grant") }
        context.pfpDataStore.edit { it[KEY_VITA3K_UX0_TREE_URI] = treeUri.toString() }
        Timber.i("Vita3K ux0 folder set to: $treeUri")
    }

    /** Forgets the granted folder (Vita discovery/trophies then go dark until re-set). */
    suspend fun clear() {
        context.pfpDataStore.edit { it.remove(KEY_VITA3K_UX0_TREE_URI) }
    }
}
