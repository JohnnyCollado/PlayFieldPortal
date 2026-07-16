package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.playfieldportal.core.data.database.entity.SteamNoAchievementsEntity
import com.playfieldportal.core.data.database.entity.SteamOwnedGameEntity

@Dao
interface SteamOwnedGamesDao {

    @Query("SELECT * FROM steam_owned_games")
    suspend fun getAll(): List<SteamOwnedGameEntity>

    @Query("SELECT synced_playtime_minutes FROM steam_owned_games WHERE appid = :appid")
    suspend fun syncedPlaytime(appid: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(games: List<SteamOwnedGameEntity>)

    // A fresh owned-games fetch replaces the cache, but each game's sync bookmark survives —
    // it is the import's memory of what has already been processed.
    @Transaction
    suspend fun replaceOwned(games: List<SteamOwnedGameEntity>) {
        val bookmarks = getAll().associate { it.appid to it.syncedPlaytimeMinutes }
        clearOwned()
        upsertAll(games.map { it.copy(syncedPlaytimeMinutes = bookmarks[it.appid]) })
    }

    @Query("DELETE FROM steam_owned_games")
    suspend fun clearOwned()

    @Query("UPDATE steam_owned_games SET synced_playtime_minutes = playtime_forever_minutes WHERE appid = :appid")
    suspend fun markSynced(appid: String)

    @Query("SELECT appid FROM steam_no_achievements")
    suspend fun noAchievementAppids(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun rememberNoAchievements(entity: SteamNoAchievementsEntity)

    @Query("SELECT EXISTS(SELECT 1 FROM steam_owned_games WHERE appid = :appid)")
    suspend fun isOwned(appid: String): Boolean

    // Distinguishes "unowned" from "the import never ran": an empty cache means ownership is
    // UNKNOWN, never NOT_IN_LIBRARY.
    @Query("SELECT COUNT(*) FROM steam_owned_games")
    suspend fun ownedCount(): Int
}
