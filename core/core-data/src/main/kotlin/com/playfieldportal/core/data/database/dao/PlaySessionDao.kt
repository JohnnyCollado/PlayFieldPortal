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

    // Distinct platforms that have sessions, ordered by most recently played
    // Used to drive Recently Played → per-platform list
    @Query("""
        SELECT platform_id
        FROM play_sessions
        GROUP BY platform_id
        ORDER BY MAX(launched_at) DESC
    """)
    fun observeRecentPlatformIds(): Flow<List<String>>

    // Most recent session per platform — for the Recently Played platform row subtitle
    @Query("""
        SELECT MAX(launched_at)
        FROM play_sessions
        WHERE platform_id = :platformId
    """)
    suspend fun getLastPlayedAt(platformId: String): Long?

    // Games played on a platform, most recent first, capped for the drill-down view
    @Query("""
        SELECT DISTINCT game_id
        FROM play_sessions
        WHERE platform_id = :platformId
        ORDER BY launched_at DESC
        LIMIT :limit
    """)
    suspend fun getRecentGameIdsForPlatform(platformId: String, limit: Int): List<Long>

    @Query("SELECT * FROM play_sessions ORDER BY launched_at DESC")
    suspend fun getAll(): List<PlaySessionEntity>

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<PlaySessionEntity>)

    @Query("DELETE FROM play_sessions")
    suspend fun deleteAll()

    @Query("DELETE FROM play_sessions WHERE game_id = :gameId")
    suspend fun deleteForGame(gameId: Long)

    // Prune oldest sessions when library exceeds the user's configured cap
    @Query("""
        DELETE FROM play_sessions
        WHERE id NOT IN (
            SELECT id FROM play_sessions ORDER BY launched_at DESC LIMIT :keepCount
        )
    """)
    suspend fun pruneOldSessions(keepCount: Int)
}
