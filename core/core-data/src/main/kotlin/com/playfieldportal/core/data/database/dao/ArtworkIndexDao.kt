package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.ArtworkIndexEntity

@Dao
interface ArtworkIndexDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<ArtworkIndexEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ArtworkIndexEntity)

    @Query("SELECT * FROM artwork_index WHERE `key` = :key")
    suspend fun getByKey(key: String): List<ArtworkIndexEntity>

    @Query("SELECT * FROM artwork_index WHERE `key` = :key AND kind = :kind")
    suspend fun get(key: String, kind: String): ArtworkIndexEntity?

    @Query("SELECT COUNT(*) FROM artwork_index")
    suspend fun count(): Int

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM artwork_index")
    suspend fun totalBytes(): Long

    @Query("DELETE FROM artwork_index WHERE `key` = :key")
    suspend fun deleteByKey(key: String)

    @Query("DELETE FROM artwork_index WHERE `key` = :key AND kind = :kind")
    suspend fun delete(key: String, kind: String)

    @Query("DELETE FROM artwork_index")
    suspend fun clear()
}
