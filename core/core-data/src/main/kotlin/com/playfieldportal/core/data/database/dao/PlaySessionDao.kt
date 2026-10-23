package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.PlaySessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaySessionDao {

    @Insert
    suspend fun insert(session: PlaySessionEntity): Long

    @Query("SELECT * FROM play_sessions WHERE game_id = :gameId ORDER BY launched_at DESC")
    fun observeForGame(gameId: Long): Flow<List<PlaySessionEntity>>

    @Query("SELECT * FROM play_sessions ORDER BY launched_at DESC")
    suspend fun getAll(): List<PlaySessionEntity>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<PlaySessionEntity>)

    @Query("DELETE FROM play_sessions")
    suspend fun deleteAll()

    @Query("DELETE FROM play_sessions WHERE game_id = :gameId")
    suspend fun deleteForGame(gameId: Long)
}
