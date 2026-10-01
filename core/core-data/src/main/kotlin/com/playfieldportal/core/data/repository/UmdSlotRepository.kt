package com.playfieldportal.core.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.playfieldportal.core.data.database.dao.UmdSlotDao
import com.playfieldportal.core.data.database.entity.UmdSlotEntity
import com.playfieldportal.core.data.datastore.pfpDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// When each column's UMD was last ejected, as "columnId\tepochMillis" entries. A DataStore set
// rather than a table column: an ejected slot holds no game, which umd_slots cannot express.
private val KEY_UMD_EJECTED_AT = stringSetPreferencesKey("umd_ejected_at")
private const val EJECT_SEPARATOR = "\t"

/**
 * The game the user inserted as each gaming column's UMD, keyed by column (category id), and when
 * a column's UMD was ejected. Which game the slot finally shows — the inserted one, the last-played
 * fallback, or none after an eject — is decided by
 * [com.playfieldportal.core.domain.model.UmdSlotResolver].
 */
@Singleton
class UmdSlotRepository @Inject constructor(
    private val umdSlotDao: UmdSlotDao,
    @ApplicationContext private val context: Context,
) {
    /** Inserted game id per column. A column absent from the map has nothing inserted. */
    val insertedGameIds: Flow<Map<String, Long>> =
        umdSlotDao.observeAll().map { slots -> slots.associate { it.columnId to it.gameId } }

    /** When each column's UMD was ejected (epoch ms). A column absent from the map never was. */
    val ejectedAt: Flow<Map<String, Long>> = context.pfpDataStore.data.map { prefs ->
        prefs[KEY_UMD_EJECTED_AT].orEmpty().mapNotNull { entry ->
            val parts = entry.split(EJECT_SEPARATOR)
            val at = parts.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
            parts[0].takeIf { it.isNotEmpty() }?.let { it to at }
        }.toMap()
    }

    /** Inserting fills the slot whatever was ejected before. */
    suspend fun insert(columnId: String, gameId: Long) {
        umdSlotDao.upsert(UmdSlotEntity(columnId, gameId, insertedAt = System.currentTimeMillis()))
        setEjectedAt(columnId, null)
        Timber.i("UMD inserted: column=$columnId game=$gameId")
    }

    /**
     * Empties the slot — an inserted game and the recently played fallback alike — until the next
     * game played from this column, or an insert.
     */
    suspend fun eject(columnId: String) {
        umdSlotDao.delete(columnId)
        setEjectedAt(columnId, System.currentTimeMillis())
        Timber.i("UMD ejected: column=$columnId")
    }

    private suspend fun setEjectedAt(columnId: String, at: Long?) {
        context.pfpDataStore.edit { prefs ->
            val others = prefs[KEY_UMD_EJECTED_AT].orEmpty()
                .filterNot { it.substringBefore(EJECT_SEPARATOR) == columnId }
                .toSet()
            prefs[KEY_UMD_EJECTED_AT] = if (at == null) others else others + "$columnId$EJECT_SEPARATOR$at"
        }
    }
}
