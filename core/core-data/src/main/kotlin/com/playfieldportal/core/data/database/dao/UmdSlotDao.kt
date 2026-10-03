package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.UmdSlotEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UmdSlotDao {

    @Query("SELECT * FROM umd_slots")
    fun observeAll(): Flow<List<UmdSlotEntity>>

    @Query("SELECT * FROM umd_slots")
    suspend fun getAll(): List<UmdSlotEntity>

    @Query("SELECT * FROM umd_slots WHERE column_id = :columnId")
    suspend fun get(columnId: String): UmdSlotEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(slot: UmdSlotEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(slots: List<UmdSlotEntity>)

    @Query("DELETE FROM umd_slots WHERE column_id = :columnId")
    suspend fun delete(columnId: String)

    @Query("DELETE FROM umd_slots")
    suspend fun clear()
}
