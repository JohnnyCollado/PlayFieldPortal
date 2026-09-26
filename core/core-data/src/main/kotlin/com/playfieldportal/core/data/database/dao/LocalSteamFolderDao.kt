package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.LocalSteamFolderEntity
import kotlinx.coroutines.flow.Flow

/**
 * Reads and writes `local_steam_folders` — the registry of game folders the user pointed PFP at.
 *
 * Deliberately small. The whole point of the table is that [getByAppId] is a primary-key read, so
 * a sync never pays for a tree walk to find a folder it has already been shown.
 */
@Dao
interface LocalSteamFolderDao {

    /** The registered folder for [appId], or null when nothing has been pointed at it. */
    @Query("SELECT * FROM local_steam_folders WHERE app_id = :appId")
    suspend fun getByAppId(appId: String): LocalSteamFolderEntity?

    @Query("SELECT * FROM local_steam_folders ORDER BY folder_name COLLATE NOCASE")
    suspend fun getAll(): List<LocalSteamFolderEntity>

    /** The registry as a Flow — what the settings listing renders. */
    @Query("SELECT * FROM local_steam_folders ORDER BY folder_name COLLATE NOCASE")
    fun observeAll(): Flow<List<LocalSteamFolderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(folder: LocalSteamFolderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(folders: List<LocalSteamFolderEntity>)

    /** Forget one folder. The game's provider link and its earned coins are untouched. */
    @Query("DELETE FROM local_steam_folders WHERE app_id = :appId")
    suspend fun deleteByAppId(appId: String)

    @Query("UPDATE local_steam_folders SET last_seen_at = :at WHERE app_id = :appId")
    suspend fun touchLastSeen(appId: String, at: Long)

    @Query("SELECT COUNT(*) FROM local_steam_folders")
    suspend fun count(): Int
}
