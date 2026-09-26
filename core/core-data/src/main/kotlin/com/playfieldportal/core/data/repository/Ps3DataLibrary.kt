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

private val KEY_PS3_DATA_TREE_URI = stringPreferencesKey("ps3_data_tree_uri")

/**
 * Holds the user's grant to ARMSX3's PS3 data folder — the single SAF permission that powers PS3
 * trophy tracking, exactly as [Vita3KLibrary] does for Vita3K's `ux0`.
 *
 * ARMSX3 keeps its data at `<shared storage>/PS3/config/dev_hdd0/`, so unlike Vita3K's often
 * app-private `ux0` a single grant always works. The reader is tolerant about which level the user
 * picks (the ARMSX3 root, `config/`, `dev_hdd0/`, or `trophy/` itself) — see `Ps3TrophyDiscovery`.
 *
 * Read-only in the strongest sense: PFP only ever takes a read grant and never writes into the
 * emulator's folder.
 */
@Singleton
class Ps3DataLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** The granted PS3 data tree URI, or null when the user hasn't set one. */
    val dataTreeUriFlow: Flow<String?> = context.pfpDataStore.data.map { it[KEY_PS3_DATA_TREE_URI] }

    /** Snapshot of [dataTreeUriFlow]. Null when unset — callers report "not configured". */
    suspend fun dataTreeUri(): String? = context.pfpDataStore.data.first()[KEY_PS3_DATA_TREE_URI]

    /** Persists a read grant on [treeUri] and stores it as the ARMSX3 PS3 data folder. */
    suspend fun setDataFolder(treeUri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }.onFailure { Timber.w(it, "Could not persist PS3 data folder grant") }
        context.pfpDataStore.edit { it[KEY_PS3_DATA_TREE_URI] = treeUri.toString() }
        Timber.i("PS3 data folder set to: $treeUri")
    }

    /** Forgets the granted folder (PS3 trophies then go dark until re-set). */
    suspend fun clear() {
        context.pfpDataStore.edit { it.remove(KEY_PS3_DATA_TREE_URI) }
    }
}
