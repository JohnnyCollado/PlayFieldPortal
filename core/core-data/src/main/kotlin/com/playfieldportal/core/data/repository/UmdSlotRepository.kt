package com.playfieldportal.core.data.repository

import com.playfieldportal.core.data.database.dao.UmdSlotDao
import com.playfieldportal.core.data.database.entity.UmdSlotEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The game the user inserted as each gaming column's UMD, keyed by column (category id). Which
 * game the slot finally shows — the inserted one or the last-played fallback — is decided by
 * [com.playfieldportal.core.domain.model.UmdSlotResolver].
 */
@Singleton
class UmdSlotRepository @Inject constructor(
    private val umdSlotDao: UmdSlotDao,
) {
    /** Inserted game id per column. A column absent from the map has nothing inserted. */
    val insertedGameIds: Flow<Map<String, Long>> =
        umdSlotDao.observeAll().map { slots -> slots.associate { it.columnId to it.gameId } }

    suspend fun insert(columnId: String, gameId: Long) {
        umdSlotDao.upsert(UmdSlotEntity(columnId, gameId, insertedAt = System.currentTimeMillis()))
        Timber.i("UMD inserted: column=$columnId game=$gameId")
    }

    suspend fun eject(columnId: String) {
        umdSlotDao.delete(columnId)
        Timber.i("UMD ejected: column=$columnId")
    }
}
