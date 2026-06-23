package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.playfieldportal.core.data.database.entity.GameEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {

    @Query("SELECT * FROM games ORDER BY title ASC")
    fun observeAll(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE is_favorite = 1 ORDER BY favorite_sort_order ASC")
    fun observeFavorites(): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE platform_id = :platformId ORDER BY title ASC")
    fun observeByPlatform(platformId: String): Flow<List<GameEntity>>

    @Query("SELECT * FROM games WHERE id = :id")
    suspend fun getById(id: Long): GameEntity?

    @Query("SELECT * FROM games WHERE rom_path = :romPath LIMIT 1")
    suspend fun getByRomPath(romPath: String): GameEntity?

    @Query("SELECT * FROM games WHERE package_name = :packageName LIMIT 1")
    suspend fun getByPackageName(packageName: String): GameEntity?

    @Query("SELECT * FROM games WHERE last_played_at IS NOT NULL ORDER BY last_played_at DESC LIMIT :limit")
    fun observeRecentlyPlayed(limit: Int): Flow<List<GameEntity>>

    // Used by recently played per-platform drill-down
    @Query("""
        SELECT * FROM games
        WHERE platform_id = :platformId
          AND last_played_at IS NOT NULL
        ORDER BY last_played_at DESC
        LIMIT :limit
    """)
    fun observeRecentByPlatform(platformId: String, limit: Int): Flow<List<GameEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(game: GameEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(games: List<GameEntity>)

    @Update
    suspend fun update(game: GameEntity)

    @Query("DELETE FROM games WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM games WHERE platform_id = :platformId")
    suspend fun deleteByPlatform(platformId: String)

    @Query("SELECT COUNT(*) FROM games WHERE platform_id = :platformId")
    suspend fun countByPlatform(platformId: String): Int

    @Query("UPDATE games SET is_favorite = :isFavorite WHERE id = :id")
    suspend fun setFavorite(id: Long, isFavorite: Boolean)

    @Query("UPDATE games SET favorite_sort_order = :order WHERE id = :id")
    suspend fun updateFavoriteSortOrder(id: Long, order: Int)

    @Query("UPDATE games SET user_note = :note WHERE id = :id")
    suspend fun updateNote(id: Long, note: String?)

    @Query("UPDATE games SET artwork_uri = :artworkUri WHERE id = :id")
    suspend fun updateArtwork(id: Long, artworkUri: String?)

    @Query("UPDATE games SET hero_uri = :heroUri WHERE id = :id")
    suspend fun updateHero(id: Long, heroUri: String?)

    @Query("UPDATE games SET logo_uri = :logoUri WHERE id = :id")
    suspend fun updateLogo(id: Long, logoUri: String?)

    @Query("UPDATE games SET hero_uri = :heroUri, logo_uri = :logoUri WHERE id = :id")
    suspend fun updateHeroAndLogo(id: Long, heroUri: String?, logoUri: String?)

    @Query("""
        UPDATE games
        SET total_play_time_millis = total_play_time_millis + :durationMillis,
            last_played_at = :playedAt
        WHERE id = :id
    """)
    suspend fun addPlayTime(id: Long, durationMillis: Long, playedAt: Long)

    @Query("""
        UPDATE games SET emulator_package = :emulatorPackage WHERE id = :id
    """)
    suspend fun setPreferredEmulator(id: Long, emulatorPackage: String?)

    // For missing ROM check — returns all games that have a rom_path
    @Query("SELECT id, rom_path FROM games WHERE rom_path IS NOT NULL")
    suspend fun getAllRomPaths(): List<RomPathProjection>

    @Query("SELECT * FROM games ORDER BY title ASC")
    suspend fun getAll(): List<GameEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllReplace(games: List<GameEntity>)

    @Query("SELECT * FROM games WHERE artwork_uri IS NULL AND rom_path IS NOT NULL")
    suspend fun getGamesWithoutArtwork(): List<GameEntity>

    // Updates only non-null fields — COALESCE keeps existing value when new value is null.
    // scraped_title is updated when a metadata source returns a title.
    // user_title_override is NEVER touched here — only explicit user action changes it.
    @Query("""
        UPDATE games SET
            description     = COALESCE(:description,  description),
            developer       = COALESCE(:developer,    developer),
            publisher       = COALESCE(:publisher,    publisher),
            release_year    = COALESCE(:releaseYear,  release_year),
            genre           = COALESCE(:genre,        genre),
            artwork_uri     = COALESCE(:artworkUri,   artwork_uri),
            hero_uri        = COALESCE(:heroUri,      hero_uri),
            logo_uri        = COALESCE(:logoUri,      logo_uri),
            icon_uri        = COALESCE(:iconUri,      icon_uri),
            scraped_title   = COALESCE(:scrapedTitle, scraped_title)
        WHERE id = :id
    """)
    suspend fun updateMetadata(
        id: Long,
        description: String?  = null,
        developer: String?    = null,
        publisher: String?    = null,
        releaseYear: Int?     = null,
        genre: String?        = null,
        artworkUri: String?   = null,
        heroUri: String?      = null,
        logoUri: String?      = null,
        iconUri: String?      = null,
        scrapedTitle: String? = null,
    )

    @Query("UPDATE games SET scraped_title = :scrapedTitle WHERE id = :id")
    suspend fun updateScrapedTitle(id: Long, scrapedTitle: String?)

    // Stores the user-chosen display name. Pass null to clear and fall back to scrapedTitle/title.
    @Query("UPDATE games SET user_title_override = :override WHERE id = :id")
    suspend fun updateUserTitleOverride(id: Long, override: String?)

    @Query("UPDATE games SET icon_uri = :iconUri WHERE id = :id")
    suspend fun updateIconUri(id: Long, iconUri: String?)

    // Clears all artwork references so a re-scrape starts from a clean slate.
    @Query("UPDATE games SET artwork_uri = NULL, hero_uri = NULL, logo_uri = NULL, icon_uri = NULL")
    suspend fun clearAllArtwork()

    @Query("UPDATE games SET artwork_uri = NULL, hero_uri = NULL, logo_uri = NULL, icon_uri = NULL WHERE id = :id")
    suspend fun clearArtworkForGame(id: Long)

    @Query("DELETE FROM games")
    suspend fun deleteAll()
}

data class RomPathProjection(
    val id: Long,
    val rom_path: String,
)
