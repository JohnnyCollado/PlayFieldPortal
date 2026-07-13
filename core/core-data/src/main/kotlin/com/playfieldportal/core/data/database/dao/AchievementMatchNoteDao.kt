package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.AchievementMatchNoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AchievementMatchNoteDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(note: AchievementMatchNoteEntity)

    @Query("DELETE FROM achievement_match_notes WHERE game_id = :gameId")
    suspend fun deleteForGame(gameId: Long)

    /** Wipes all notes — the auto-matcher rewrites them from scratch each run. */
    @Query("DELETE FROM achievement_match_notes")
    suspend fun clear()

    @Query("SELECT * FROM achievement_match_notes")
    fun observeAll(): Flow<List<AchievementMatchNoteEntity>>
}
