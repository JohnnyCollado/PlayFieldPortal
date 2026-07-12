package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.SsMediaCacheEntity

@Dao
interface SsMediaCacheDao {

    @Query("SELECT * FROM ss_media_cache WHERE ss_id = :ssId")
    suspend fun get(ssId: Long): SsMediaCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SsMediaCacheEntity)

    // Dead-URL fallback: drop the row so the next lookup refreshes from a live jeuInfos.
    @Query("DELETE FROM ss_media_cache WHERE ss_id = :ssId")
    suspend fun delete(ssId: Long)

    @Query("DELETE FROM ss_media_cache")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM ss_media_cache")
    suspend fun count(): Int
}
