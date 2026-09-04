package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.LaunchOutcomeEntity

@Dao
interface LaunchOutcomeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(outcome: LaunchOutcomeEntity): Long

    /** The most recent outcomes for one game, newest first — the recovery sheet's history line. */
    @Query(
        "SELECT * FROM launch_outcomes WHERE game_id = :gameId " +
            "ORDER BY launched_at_ms DESC LIMIT :limit"
    )
    suspend fun recentForGame(gameId: Long, limit: Int): List<LaunchOutcomeEntity>

    /** The most recent outcomes for a platform, newest first — alternate-emulator offers. */
    @Query(
        "SELECT * FROM launch_outcomes WHERE platform_id = :platformId " +
            "ORDER BY launched_at_ms DESC LIMIT :limit"
    )
    suspend fun recentForPlatform(platformId: String, limit: Int): List<LaunchOutcomeEntity>

    /** Most recent failed outcomes for one game, newest first — "failed N of the last M times". */
    @Query(
        "SELECT * FROM launch_outcomes WHERE game_id = :gameId AND outcome != 'SUCCEEDED' " +
            "ORDER BY launched_at_ms DESC LIMIT :limit"
    )
    suspend fun recentFailuresForGame(gameId: Long, limit: Int): List<LaunchOutcomeEntity>
}
