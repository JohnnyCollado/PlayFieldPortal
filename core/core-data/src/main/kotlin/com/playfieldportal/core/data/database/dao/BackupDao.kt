package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.playfieldportal.core.data.database.entity.AccountAchievementEntity
import com.playfieldportal.core.data.database.entity.AccountAchievementSetEntity
import com.playfieldportal.core.data.database.entity.AchievementProviderSyncStateEntity
import com.playfieldportal.core.data.database.entity.AchievementTrackedIdentityEntity
import com.playfieldportal.core.data.database.entity.ProviderGameLinkEntity
import com.playfieldportal.core.data.database.entity.AppOverrideEntity
import com.playfieldportal.core.data.database.entity.CollectionEntity
import com.playfieldportal.core.data.database.entity.CollectionGameEntity
import com.playfieldportal.core.data.database.entity.HiddenPlacementEntity
import com.playfieldportal.core.data.database.entity.MemoryCardEntity
import com.playfieldportal.core.data.database.entity.MusicFolderEntity
import com.playfieldportal.core.data.database.entity.MusicTrackEntity
import com.playfieldportal.core.data.database.entity.PhotoEntity
import com.playfieldportal.core.data.database.entity.PhotoLibraryEntity
import com.playfieldportal.core.data.database.entity.PlatformEntity
import com.playfieldportal.core.data.database.entity.PlaylistEntity
import com.playfieldportal.core.data.database.entity.PlaylistTrackEntity
import com.playfieldportal.core.data.database.entity.ThemeEntity
import com.playfieldportal.core.data.database.entity.VideoEntity
import com.playfieldportal.core.data.database.entity.VideoLibraryEntity
import com.playfieldportal.core.data.database.entity.VideoPlaylistEntity
import com.playfieldportal.core.data.database.entity.VideoPlaylistItemEntity

/**
 * Backup-only data access. Centralises the read / wipe / bulk-insert queries the backup format-v2
 * export & restore needs for the tables the feature DAOs don't already expose that way, so those
 * DAOs stay focused on the app's own use cases.
 *
 * `platforms` is deliberately NOT wiped on restore — it is a seeded catalog with a few
 * user-editable columns. [restorePlatformPrefs] updates only those columns so a backup taken by an
 * older build can't erase platform definitions the current build added. `themes` is likewise not
 * wiped (built-ins are seeded once and never re-seeded); backed-up themes are upserted and the
 * active one re-asserted via [setActiveTheme].
 */
@Dao
interface BackupDao {

    // ── Export getters ──────────────────────────────────────────────────
    @Query("SELECT * FROM platforms")             suspend fun getPlatforms(): List<PlatformEntity>
    @Query("SELECT * FROM memory_cards")          suspend fun getMemoryCards(): List<MemoryCardEntity>
    @Query("SELECT * FROM app_overrides")         suspend fun getAppOverrides(): List<AppOverrideEntity>
    @Query("SELECT * FROM collections")           suspend fun getCollections(): List<CollectionEntity>
    @Query("SELECT * FROM collection_games")      suspend fun getCollectionGames(): List<CollectionGameEntity>
    @Query("SELECT * FROM themes")                suspend fun getThemes(): List<ThemeEntity>
    @Query("SELECT * FROM hidden_placements")     suspend fun getHiddenPlacements(): List<HiddenPlacementEntity>
    @Query("SELECT * FROM music_folders")         suspend fun getMusicFolders(): List<MusicFolderEntity>
    @Query("SELECT * FROM music_tracks")          suspend fun getMusicTracks(): List<MusicTrackEntity>
    @Query("SELECT * FROM playlists")             suspend fun getPlaylists(): List<PlaylistEntity>
    @Query("SELECT * FROM playlist_tracks")       suspend fun getPlaylistTracks(): List<PlaylistTrackEntity>
    @Query("SELECT * FROM video_libraries")       suspend fun getVideoLibraries(): List<VideoLibraryEntity>
    @Query("SELECT * FROM videos")                suspend fun getVideos(): List<VideoEntity>
    @Query("SELECT * FROM video_playlists")       suspend fun getVideoPlaylists(): List<VideoPlaylistEntity>
    @Query("SELECT * FROM video_playlist_items")  suspend fun getVideoPlaylistItems(): List<VideoPlaylistItemEntity>
    @Query("SELECT * FROM photo_libraries")       suspend fun getPhotoLibraries(): List<PhotoLibraryEntity>
    @Query("SELECT * FROM photos")                suspend fun getPhotos(): List<PhotoEntity>

    // ── Bulk insert (REPLACE so explicit primary keys from the backup are honoured) ──
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMemoryCards(rows: List<MemoryCardEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAppOverrides(rows: List<AppOverrideEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCollections(rows: List<CollectionEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCollectionGames(rows: List<CollectionGameEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertThemes(rows: List<ThemeEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertHiddenPlacements(rows: List<HiddenPlacementEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMusicFolders(rows: List<MusicFolderEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMusicTracks(rows: List<MusicTrackEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPlaylists(rows: List<PlaylistEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPlaylistTracks(rows: List<PlaylistTrackEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertVideoLibraries(rows: List<VideoLibraryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertVideos(rows: List<VideoEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertVideoPlaylists(rows: List<VideoPlaylistEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertVideoPlaylistItems(rows: List<VideoPlaylistItemEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPhotoLibraries(rows: List<PhotoLibraryEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertPhotos(rows: List<PhotoEntity>)

    // ── Wipe (child rows first; parents rely on this ordering, not just FK cascade) ──
    @Query("DELETE FROM collection_games")      suspend fun clearCollectionGames()
    @Query("DELETE FROM collections")           suspend fun clearCollections()
    @Query("DELETE FROM playlist_tracks")       suspend fun clearPlaylistTracks()
    @Query("DELETE FROM playlists")             suspend fun clearPlaylists()
    @Query("DELETE FROM music_tracks")          suspend fun clearMusicTracks()
    @Query("DELETE FROM music_folders")         suspend fun clearMusicFolders()
    @Query("DELETE FROM video_playlist_items")  suspend fun clearVideoPlaylistItems()
    @Query("DELETE FROM video_playlists")       suspend fun clearVideoPlaylists()
    @Query("DELETE FROM videos")                suspend fun clearVideos()
    @Query("DELETE FROM video_libraries")       suspend fun clearVideoLibraries()
    @Query("DELETE FROM photos")                suspend fun clearPhotos()
    @Query("DELETE FROM photo_libraries")       suspend fun clearPhotoLibraries()
    @Query("DELETE FROM memory_cards")          suspend fun clearMemoryCards()
    @Query("DELETE FROM app_overrides")         suspend fun clearAppOverrides()
    @Query("DELETE FROM hidden_placements")     suspend fun clearHiddenPlacements()
    @Query("DELETE FROM category_items")        suspend fun clearCategoryItems()

    // ── Platform / theme merge helpers (no wipe) ────────────────────────
    @Query(
        """
        UPDATE platforms
        SET preferred_emulator_package = :preferredEmulatorPackage,
            is_pinned_to_bar = :isPinnedToBar,
            bar_position = :barPosition
        WHERE id = :id
        """
    )
    suspend fun restorePlatformPrefs(
        id: String,
        preferredEmulatorPackage: String?,
        isPinnedToBar: Boolean,
        barPosition: Int,
    )

    @Query("UPDATE themes SET is_active = (id = :id)")
    suspend fun setActiveTheme(id: String)

    // ── Achievements (selective sync) ───────────────────────────────────
    @Query("SELECT * FROM achievement_tracked_identities")   suspend fun getAchievementIdentities(): List<AchievementTrackedIdentityEntity>
    @Query("SELECT * FROM achievement_provider_sync_state")  suspend fun getAchievementSyncStates(): List<AchievementProviderSyncStateEntity>
    @Query("SELECT * FROM account_achievement_sets")         suspend fun getAchievementSets(): List<AccountAchievementSetEntity>
    @Query("SELECT * FROM account_achievements")             suspend fun getAchievementCoins(): List<AccountAchievementEntity>
    @Query("SELECT * FROM provider_game_links")              suspend fun getProviderGameLinks(): List<ProviderGameLinkEntity>

    @Query("DELETE FROM achievement_tracked_identities")  suspend fun clearAchievementIdentities()
    @Query("DELETE FROM achievement_provider_sync_state") suspend fun clearAchievementSyncStates()
    @Query("DELETE FROM account_achievements")            suspend fun clearAchievementCoins()
    @Query("DELETE FROM account_achievement_sets")        suspend fun clearAchievementSets()
    @Query("DELETE FROM provider_game_links")             suspend fun clearProviderGameLinks()

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAchievementIdentities(rows: List<AchievementTrackedIdentityEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAchievementSyncStates(rows: List<AchievementProviderSyncStateEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAchievementSets(rows: List<AccountAchievementSetEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAchievementCoins(rows: List<AccountAchievementEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertProviderGameLinks(rows: List<ProviderGameLinkEntity>)

    /**
     * Replaces the achievement records with a backup's, in one transaction. Runs after games are
     * restored; the caller passes only links whose game the backup restored.
     */
    @Transaction
    suspend fun replaceAchievementRecords(
        identities: List<AchievementTrackedIdentityEntity>,
        syncStates: List<AchievementProviderSyncStateEntity>,
        sets: List<AccountAchievementSetEntity>,
        coins: List<AccountAchievementEntity>,
        links: List<ProviderGameLinkEntity>,
    ) {
        clearAchievementCoins()
        clearAchievementSets()
        clearAchievementIdentities()
        clearAchievementSyncStates()
        clearProviderGameLinks()
        insertAchievementIdentities(identities)
        insertAchievementSyncStates(syncStates)
        insertAchievementSets(sets)
        insertAchievementCoins(coins)
        insertProviderGameLinks(links)
    }
}
