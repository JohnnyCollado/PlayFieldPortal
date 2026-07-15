package com.playfieldportal.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.playfieldportal.core.data.database.dao.AccountAchievementDao
import com.playfieldportal.core.data.database.dao.AccountAchievementSetDao
import com.playfieldportal.core.data.database.dao.AppOverrideDao
import com.playfieldportal.core.data.database.dao.ArtworkImportReportDao
import com.playfieldportal.core.data.database.dao.ArtworkRecordDao
import com.playfieldportal.core.data.database.dao.BackupDao
import com.playfieldportal.core.data.database.dao.CategoryDao
import com.playfieldportal.core.data.database.dao.CollectionDao
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.dao.LibrarySourceDao
import com.playfieldportal.core.data.database.dao.MemoryCardDao
import com.playfieldportal.core.data.database.dao.MusicFolderDao
import com.playfieldportal.core.data.database.dao.MusicTrackDao
import com.playfieldportal.core.data.database.dao.PlaylistDao
import com.playfieldportal.core.data.database.dao.PlaySessionDao
import com.playfieldportal.core.data.database.dao.ProviderGameLinkDao
import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.dao.ThemeDao
import com.playfieldportal.core.data.database.dao.UnmatchedRomDao
import com.playfieldportal.core.data.database.dao.HiddenPlacementDao
import com.playfieldportal.core.data.database.dao.PhotoDao
import com.playfieldportal.core.data.database.dao.PhotoLibraryDao
import com.playfieldportal.core.data.database.dao.ScanTombstoneDao
import com.playfieldportal.core.data.database.dao.SsMediaCacheDao
import com.playfieldportal.core.data.database.dao.VideoDao
import com.playfieldportal.core.data.database.dao.VideoLibraryDao
import com.playfieldportal.core.data.database.dao.VideoPlaylistDao
import com.playfieldportal.core.data.database.entity.AccountAchievementEntity
import com.playfieldportal.core.data.database.entity.AccountAchievementSetEntity
import com.playfieldportal.core.data.database.entity.AchievementMatchNoteEntity
import com.playfieldportal.core.data.database.entity.AppOverrideEntity
import com.playfieldportal.core.data.database.entity.ArtworkImportReportEntity
import com.playfieldportal.core.data.database.entity.ArtworkRecordEntity
import com.playfieldportal.core.data.database.entity.CategoryEntity
import com.playfieldportal.core.data.database.entity.CategoryItemEntity
import com.playfieldportal.core.data.database.entity.CollectionEntity
import com.playfieldportal.core.data.database.entity.CollectionGameEntity
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.LibrarySourceEntity
import com.playfieldportal.core.data.database.entity.MemoryCardEntity
import com.playfieldportal.core.data.database.entity.MusicFolderEntity
import com.playfieldportal.core.data.database.entity.MusicTrackEntity
import com.playfieldportal.core.data.database.entity.PlaylistEntity
import com.playfieldportal.core.data.database.entity.PlaylistTrackEntity
import com.playfieldportal.core.data.database.entity.PlaySessionEntity
import com.playfieldportal.core.data.database.entity.ProviderGameLinkEntity
import com.playfieldportal.core.data.database.entity.PlatformEntity
import com.playfieldportal.core.data.database.entity.ThemeEntity
import com.playfieldportal.core.data.database.entity.UnmatchedRomEntity
import com.playfieldportal.core.data.database.entity.HiddenPlacementEntity
import com.playfieldportal.core.data.database.entity.PhotoEntity
import com.playfieldportal.core.data.database.entity.PhotoLibraryEntity
import com.playfieldportal.core.data.database.entity.ScanTombstoneEntity
import com.playfieldportal.core.data.database.entity.SsMediaCacheEntity
import com.playfieldportal.core.data.database.entity.VideoEntity
import com.playfieldportal.core.data.database.entity.VideoLibraryEntity
import com.playfieldportal.core.data.database.entity.VideoPlaylistEntity
import com.playfieldportal.core.data.database.entity.VideoPlaylistItemEntity

@Database(
    entities = [
        GameEntity::class,
        PlatformEntity::class,
        CategoryEntity::class,
        CategoryItemEntity::class,
        PlaySessionEntity::class,
        LibrarySourceEntity::class,
        UnmatchedRomEntity::class,
        ThemeEntity::class,
        MemoryCardEntity::class,
        AppOverrideEntity::class,
        CollectionEntity::class,
        CollectionGameEntity::class,
        MusicFolderEntity::class,
        MusicTrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackEntity::class,
        VideoLibraryEntity::class,
        VideoEntity::class,
        VideoPlaylistEntity::class,
        VideoPlaylistItemEntity::class,
        HiddenPlacementEntity::class,
        PhotoLibraryEntity::class,
        PhotoEntity::class,
        ScanTombstoneEntity::class,
        ArtworkRecordEntity::class,
        ArtworkImportReportEntity::class,
        SsMediaCacheEntity::class,
        AccountAchievementSetEntity::class,
        AccountAchievementEntity::class,
        ProviderGameLinkEntity::class,
        AchievementMatchNoteEntity::class,
    ],
    version = 33,
    exportSchema = true,        // schema JSON exported to /schemas/ for migration auditing
)
@TypeConverters(PFPTypeConverters::class)
abstract class PFPDatabase : RoomDatabase() {

    abstract fun gameDao(): GameDao
    abstract fun platformDao(): PlatformDao
    abstract fun categoryDao(): CategoryDao
    abstract fun playSessionDao(): PlaySessionDao
    abstract fun librarySourceDao(): LibrarySourceDao
    abstract fun unmatchedRomDao(): UnmatchedRomDao
    abstract fun themeDao(): ThemeDao
    abstract fun memoryCardDao(): MemoryCardDao
    abstract fun appOverrideDao(): AppOverrideDao
    abstract fun collectionDao(): CollectionDao
    abstract fun musicFolderDao(): MusicFolderDao
    abstract fun musicTrackDao(): MusicTrackDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun videoLibraryDao(): VideoLibraryDao
    abstract fun videoDao(): VideoDao
    abstract fun videoPlaylistDao(): VideoPlaylistDao
    abstract fun hiddenPlacementDao(): HiddenPlacementDao
    abstract fun photoLibraryDao(): PhotoLibraryDao
    abstract fun photoDao(): PhotoDao
    abstract fun scanTombstoneDao(): ScanTombstoneDao
    abstract fun backupDao(): BackupDao
    abstract fun artworkRecordDao(): ArtworkRecordDao
    abstract fun artworkImportReportDao(): ArtworkImportReportDao
    abstract fun ssMediaCacheDao(): SsMediaCacheDao
    abstract fun accountAchievementSetDao(): AccountAchievementSetDao
    abstract fun accountAchievementDao(): AccountAchievementDao
    abstract fun providerGameLinkDao(): ProviderGameLinkDao
    abstract fun achievementMatchNoteDao(): com.playfieldportal.core.data.database.dao.AchievementMatchNoteDao

    companion object {
        const val DATABASE_NAME = "pfp_database"

        // Migration stubs — add real SQL here as schema evolves
        // Never use fallbackToDestructiveMigration() in production — users lose their library
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE themes ADD COLUMN is_active INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // platform_id ties a scan folder to a specific platform —
                // all ROMs found there are assigned to that platform regardless of extension
                db.execSQL("ALTER TABLE library_sources ADD COLUMN platform_id TEXT")
            }
        }

        // v4 — manual Memory Card library system. Adds the memory_cards table that drives
        // the Games category. The legacy auto-detected library is retired: existing scanned
        // games and library_sources are cleared so the user starts from a clean slate and
        // configures each console manually. Platform definitions (the pick-list catalog) are
        // left intact.
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS memory_cards (
                        platform_id TEXT NOT NULL PRIMARY KEY,
                        display_name TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        pinned INTEGER NOT NULL DEFAULT 0,
                        sort_order INTEGER NOT NULL DEFAULT 0,
                        rom_directory TEXT,
                        supported_extensions TEXT NOT NULL DEFAULT '',
                        emulator_id TEXT,
                        scan_recursively INTEGER NOT NULL DEFAULT 1,
                        last_scanned_at INTEGER,
                        game_count INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                // Clean slate: retire the legacy auto-scanned library.
                db.execSQL("DELETE FROM games")
                db.execSQL("DELETE FROM library_sources")
            }
        }

        // v5 — application categories & user customization. Adds per-app overrides, a pinned
        // flag for category items, the App Store category, and aligns built-in category names
        // with the PSP-style spec. User customizations to existing categories are preserved.
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE category_items ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS app_overrides (
                        package_name TEXT NOT NULL PRIMARY KEY,
                        custom_label TEXT,
                        is_hidden INTEGER NOT NULL DEFAULT 0,
                        customized INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                // Add the App Store category (idempotent) and align built-in display names.
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO categories (id, name, icon_key, type, position, is_visible)
                    VALUES ('app_store', 'App Store', 'ic_appstore', 'BUILT_IN', 6, 1)
                    """.trimIndent()
                )
                db.execSQL("UPDATE categories SET name = 'Photo' WHERE id = 'photos' AND name = 'Photos'")
                db.execSQL("UPDATE categories SET name = 'Video' WHERE id = 'videos' AND name = 'Videos'")
                db.execSQL("UPDATE categories SET name = 'Game'  WHERE id = 'games'  AND name = 'Games'")
            }
        }

        // v6 — landscape game icon art (SteamGridDB horizontal grid) used for the 144:80 tile.
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN icon_uri TEXT")
            }
        }

        // v7 — display title fields: scraped_title (from metadata) and user_title_override
        // (user-set). displayTitle = userTitleOverride ?: scrapedTitle ?: title.
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN scraped_title TEXT")
                db.execSQL("ALTER TABLE games ADD COLUMN user_title_override TEXT")
            }
        }

        // v8 — user collections + game content typing.
        //  • content_type classifies each game so "All Games" can aggregate real games only.
        //    Backfill reclassifies existing app-style entries (package-based) as ANDROID_APP so
        //    they drop out of All Games but stay in their own card/category. No rows are deleted.
        //  • collections / collection_games implement user-created folders (many-to-many).
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN content_type TEXT NOT NULL DEFAULT 'GAME'")
                // Existing package-based (Android app) entries are not real games — reclassify
                // so they no longer appear in All Games automatically. Their rows are preserved.
                db.execSQL("UPDATE games SET content_type = 'ANDROID_APP' WHERE package_name IS NOT NULL")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS collections (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        sort_order INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS collection_games (
                        collection_id INTEGER NOT NULL,
                        game_id INTEGER NOT NULL,
                        added_at INTEGER NOT NULL,
                        PRIMARY KEY(collection_id, game_id),
                        FOREIGN KEY(collection_id) REFERENCES collections(id) ON UPDATE NO ACTION ON DELETE CASCADE,
                        FOREIGN KEY(game_id) REFERENCES games(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_collection_games_game_id ON collection_games (game_id)"
                )
            }
        }

        // v9 — launcher-shortcut entries: per-game shortcuts harvested from other apps
        // (GameHub PCs, etc.) store the host app's shortcut id so they can be launched via
        // LauncherApps.startShortcut. Nullable; existing rows are unaffected.
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN launch_shortcut_id TEXT")
            }
        }

        // v10 — legacy INSTALL_SHORTCUT capture: stores the broadcast's launch intent so PFP can
        // launch shortcuts from apps that still use the old broadcast (BannerHub, old Winlator).
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN launch_intent_uri TEXT")
            }
        }

        // v11 — collections belong to exactly one gaming category. category_id is the single
        // source of truth for a collection's placement; existing collections default to the
        // built-in Game category ('games').
        val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE collections ADD COLUMN category_id TEXT NOT NULL DEFAULT 'games'")
            }
        }

        // v12 — collections can be pinned to the top of their category.
        val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE collections ADD COLUMN is_pinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v13 — Xbox 360 platform (X360 Mobile emulator). Adds the built-in platform definition
        // to databases seeded by older builds. Idempotent; user customizations are preserved.
        // accent_color 0xFF107C10 (Xbox green) = 4279270416.
        val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO platforms
                        (id, name, short_name, icon_res, accent_color, is_pinned_to_bar,
                         bar_position, preferred_emulator_package, rom_extensions)
                    VALUES
                        ('x360', 'Xbox 360', 'X360', 'ic_platform_xbox360', 4279270416, 0,
                         -1, 'emu.x360.mobile', 'iso,xex,zar,xbla')
                    """.trimIndent()
                )
            }
        }

        // v14 — Music library. Adds music_folders (user-added SAF sources) and music_tracks
        // (scanned audio, cascade-deleted with their folder). Additive only; no existing data
        // is touched.
        val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS music_folders (
                        id TEXT NOT NULL PRIMARY KEY,
                        display_name TEXT NOT NULL,
                        tree_uri TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        track_count INTEGER NOT NULL DEFAULT 0,
                        last_scanned_at INTEGER,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS music_tracks (
                        id TEXT NOT NULL PRIMARY KEY,
                        folder_id TEXT NOT NULL,
                        uri TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        title TEXT,
                        artist TEXT,
                        album TEXT,
                        duration_ms INTEGER,
                        mime_type TEXT,
                        size_bytes INTEGER,
                        last_modified INTEGER,
                        track_number INTEGER,
                        relative_path TEXT,
                        FOREIGN KEY(folder_id) REFERENCES music_folders(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_music_tracks_folder_id ON music_tracks (folder_id)"
                )
            }
        }

        // v15 — album art on tracks + user playlists. Adds music_tracks.art_uri (cached embedded
        // art), and the playlists / playlist_tracks tables (an ordered, cross-folder track list).
        // Additive only; no existing data is touched.
        val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE music_tracks ADD COLUMN art_uri TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS playlists (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        sort_order INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS playlist_tracks (
                        playlist_id INTEGER NOT NULL,
                        track_id TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        added_at INTEGER NOT NULL,
                        PRIMARY KEY(playlist_id, track_id),
                        FOREIGN KEY(playlist_id) REFERENCES playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_playlist_tracks_playlist_id ON playlist_tracks (playlist_id)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_playlist_tracks_track_id ON playlist_tracks (track_id)"
                )
            }
        }

        // v16 — Video Experience (Phase 1). Adds video_libraries (SAF video sources, mirroring
        // music_folders + memory cards) and videos (scanned files with metadata, thumbnails and
        // resume position). No existing data is touched.
        val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS video_libraries (
                        id TEXT NOT NULL PRIMARY KEY,
                        display_name TEXT NOT NULL,
                        tree_uri TEXT NOT NULL,
                        artwork_uri TEXT,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        scan_recursively INTEGER NOT NULL DEFAULT 1,
                        video_count INTEGER NOT NULL DEFAULT 0,
                        last_scanned_at INTEGER,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS videos (
                        id TEXT NOT NULL PRIMARY KEY,
                        library_id TEXT NOT NULL,
                        uri TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        title TEXT,
                        duration_ms INTEGER,
                        width INTEGER,
                        height INTEGER,
                        frame_rate REAL,
                        codec TEXT,
                        mime_type TEXT,
                        size_bytes INTEGER,
                        date_added INTEGER,
                        last_modified INTEGER,
                        relative_path TEXT,
                        thumbnail_uri TEXT,
                        custom_thumbnail_uri TEXT,
                        resume_position_ms INTEGER NOT NULL DEFAULT 0,
                        last_watched_at INTEGER,
                        FOREIGN KEY(library_id) REFERENCES video_libraries(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_library_id ON videos (library_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_videos_uri ON videos (uri)")
            }
        }

        // v17 — Video Experience (Phase 2). Adds a favorite flag on videos and the video-playlist
        // tables (mirroring the music playlist tables). Additive only — no existing data touched.
        val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE videos ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS video_playlists (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        sort_order INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS video_playlist_items (
                        playlist_id INTEGER NOT NULL,
                        video_id TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        added_at INTEGER NOT NULL,
                        PRIMARY KEY(playlist_id, video_id),
                        FOREIGN KEY(playlist_id) REFERENCES video_playlists(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_video_playlist_items_playlist_id ON video_playlist_items (playlist_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_video_playlist_items_video_id ON video_playlist_items (video_id)")
            }
        }

        // v18 — per-location hiding. Adds hidden_placements ("this item is hidden from this
        // location"). The legacy global app hide (app_overrides.is_hidden) is left untouched and
        // treated as a GLOBAL placement by the manager. Additive only.
        val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS hidden_placements (
                        item_key TEXT NOT NULL,
                        item_label TEXT NOT NULL,
                        location_type TEXT NOT NULL,
                        location_id TEXT NOT NULL,
                        location_label TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        PRIMARY KEY(item_key, location_type, location_id)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_hidden_placements_item_key ON hidden_placements (item_key)")
            }
        }

        // v19 — Photo section. Adds photo_libraries (SAF photo sources / Albums, mirroring
        // video_libraries) and photos (scanned files with resolution, EXIF date and a cached
        // thumbnail). Additive only; no existing data is touched.
        val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS photo_libraries (
                        id TEXT NOT NULL PRIMARY KEY,
                        display_name TEXT NOT NULL,
                        tree_uri TEXT NOT NULL,
                        enabled INTEGER NOT NULL DEFAULT 1,
                        scan_recursively INTEGER NOT NULL DEFAULT 1,
                        photo_count INTEGER NOT NULL DEFAULT 0,
                        last_scanned_at INTEGER,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS photos (
                        id TEXT NOT NULL PRIMARY KEY,
                        library_id TEXT NOT NULL,
                        uri TEXT NOT NULL,
                        display_name TEXT NOT NULL,
                        width INTEGER,
                        height INTEGER,
                        date_taken INTEGER,
                        last_modified INTEGER,
                        size_bytes INTEGER,
                        mime_type TEXT,
                        relative_path TEXT,
                        thumbnail_uri TEXT,
                        date_added INTEGER,
                        FOREIGN KEY(library_id) REFERENCES photo_libraries(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_library_id ON photos (library_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_photos_uri ON photos (uri)")
            }
        }

        // v20 — SAF ROM libraries. Adds memory_cards.tree_uri (the persisted SAF document-tree URI
        // a card scans, when the user picked it via the folder picker) and games.rom_uri (the SAF
        // content:// URI for a scanned ROM). Both nullable and additive: existing raw-path cards and
        // games are untouched (tree_uri / rom_uri stay NULL and the legacy raw-path launch path runs).
        val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE memory_cards ADD COLUMN tree_uri TEXT")
                db.execSQL("ALTER TABLE games ADD COLUMN rom_uri TEXT")
            }
        }

        // v21 — per-collection icon. Adds collections.icon_key: a user-picked key from the shared
        // category icon catalog. Nullable; NULL keeps the default memory-card art. Additive only.
        val MIGRATION_20_21 = object : Migration(20, 21) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE collections ADD COLUMN icon_key TEXT")
            }
        }

        // v22 — library restructure: Windows Games card + gaming/standard app split.
        //  • scan_tombstones records user-removed scanned games so re-scans don't resurrect them.
        //  • PC-launcher entries (harvested shortcuts / exported PC games) consolidate under the
        //    "windows" platform as real games. Only the unambiguous launcher packages are re-homed
        //    here; spoof-named variants (AnTuTu/PUBG/Genshin/CrossFire package names shared with
        //    genuine apps) are resolved by LibraryConsolidation with an app-label check.
        //  • Android-library entries become contentType GAME (they now count in All Games); the
        //    app_shortcut sentinel rows (per-app decoration/favorites shortcuts) stay ANDROID_APP.
        //  No rows are deleted. Dedupe, Windows card creation, and launcher-collection cleanup run
        //  in LibraryConsolidation (Kotlin one-shot) — they need logic SQL can't express safely.
        val MIGRATION_21_22 = object : Migration(21, 22) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS scan_tombstones (
                        rom_path TEXT NOT NULL,
                        platform_id TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        PRIMARY KEY(rom_path)
                    )
                    """.trimIndent()
                )

                // Rename only if the user hasn't customized the platform name.
                db.execSQL(
                    "UPDATE platforms SET name = 'Windows Games' WHERE id = 'windows' AND name = 'Windows (Winlator)'"
                )

                // Re-home PC-launcher game entries to the Windows platform as real games. The
                // launch-handle requirement keeps a decorated launcher app itself (a plain
                // package shortcut row with no shortcut id / intent) out of the Windows card.
                db.execSQL(
                    """
                    UPDATE games SET platform_id = 'windows', content_type = 'GAME'
                    WHERE platform_id IN ('app_shortcut', 'windows')
                      AND package_name IN ('com.winlator', 'com.winlator.cmod', 'app.gamenative',
                                           'gamehub.lite', 'banner.hub', 'com.xiaoji.egggame')
                      AND (launch_shortcut_id IS NOT NULL OR launch_intent_uri IS NOT NULL)
                    """.trimIndent()
                )

                // Android-library entries are user-curated games — promote them so they aggregate
                // into All Games and become eligible for gaming categories. The user can demote
                // individual apps via "Unmark as Game".
                db.execSQL(
                    "UPDATE games SET content_type = 'GAME' WHERE platform_id = 'android' AND content_type = 'ANDROID_APP'"
                )
            }
        }

        // v23 — zipped-ROM support for cartridge platforms. The dominant Android emulators for
        // these systems (RetroArch cores, Snes9x EX+, M64Plus FZ, My Boy!/mGBA, DraStic, MD.emu,
        // Stella/Handy/Beetle cores, VICE) all load .zip directly, so it joins the default
        // extension lists. Disc-based systems are excluded — their compressed formats are
        // CHD/RVZ/CSO and their emulators don't read zip. Existing Memory Cards get the new
        // extension too (idempotent, additive; a card the user already gave zip is untouched).
        val MIGRATION_22_23 = object : Migration(22, 23) {
            override fun migrate(db: SupportSQLiteDatabase) {
                val zipPlatforms = listOf(
                    "nes", "snes", "n64", "gb", "gbc", "gba", "nds", "virtualboy",
                    "megadrive", "mastersystem", "gamegear", "sega32x",
                    "atari2600", "atari5200", "atari7800", "atarilynx",
                    "pcengine", "ngp", "wonderswan", "wonderswancolor", "c64",
                ).joinToString(",") { "'$it'" }
                db.execSQL(
                    """
                    UPDATE platforms SET rom_extensions = rom_extensions || ',zip'
                    WHERE id IN ($zipPlatforms)
                      AND rom_extensions != '' AND rom_extensions NOT LIKE '%zip%'
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE memory_cards SET supported_extensions = supported_extensions || ',zip'
                    WHERE platform_id IN ($zipPlatforms)
                      AND supported_extensions != '' AND supported_extensions NOT LIKE '%zip%'
                    """.trimIndent()
                )
            }
        }

        // Scraper identity columns: persisted database ids let re-scrapes fetch by id instead of
        // re-matching by name, and rom_crc32 (streamed hash computed during ScreenScraper lookups)
        // is the portable-artwork matcher's strongest reconnect evidence. All nullable/additive.
        val MIGRATION_23_24 = object : Migration(23, 24) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN ss_id INTEGER")
                db.execSQL("ALTER TABLE games ADD COLUMN tgdb_id INTEGER")
                db.execSQL("ALTER TABLE games ADD COLUMN igdb_id INTEGER")
                db.execSQL("ALTER TABLE games ADD COLUMN rom_crc32 TEXT")
            }
        }

        // v25 — portable artwork library + ES-DE import. games.artwork_key is the stable portable
        // identity (rom/{platform}/{slug}, minted lazily). artwork_index is the fast lookup cache
        // over the user's artwork folder (rebuildable — never source of truth). artwork_import_reports
        // stores past import runs for the Import Report screen. All additive.
        val MIGRATION_24_25 = object : Migration(24, 25) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN artwork_key TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_games_artwork_key ON games (artwork_key)")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS artwork_index (
                        `key` TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        location TEXT NOT NULL,
                        doc_uri_or_path TEXT NOT NULL,
                        size_bytes INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL,
                        PRIMARY KEY(`key`, kind)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS artwork_import_reports (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        source TEXT NOT NULL,
                        started_at INTEGER NOT NULL,
                        duration_ms INTEGER NOT NULL,
                        summary_json TEXT NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        // v26 — portable media library layout v2. artwork_records replaces artwork_index: the
        // same fast map over the user's artwork folder, plus provenance (source, user_assigned,
        // locked) and lazily-filled dimensions/checksum. Existing index rows migrate by joining
        // games on artwork_key (multi-row disc games correctly fan out to one record per row);
        // relative_path/portable_name backfill on the next Relink — the folder, not this table,
        // is the source of truth.
        val MIGRATION_25_26 = object : Migration(25, 26) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS artwork_records (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        game_id INTEGER NOT NULL,
                        platform_id TEXT NOT NULL,
                        artwork_type TEXT NOT NULL,
                        portable_name TEXT NOT NULL,
                        relative_path TEXT NOT NULL,
                        document_uri TEXT NOT NULL,
                        source TEXT NOT NULL,
                        size_bytes INTEGER NOT NULL,
                        width INTEGER,
                        height INTEGER,
                        checksum TEXT,
                        user_assigned INTEGER NOT NULL,
                        locked INTEGER NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS index_artwork_records_game_id_artwork_type " +
                        "ON artwork_records (game_id, artwork_type)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_artwork_records_platform_id_artwork_type_portable_name " +
                        "ON artwork_records (platform_id, artwork_type, portable_name)"
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO artwork_records
                        (game_id, platform_id, artwork_type, portable_name, relative_path,
                         document_uri, source, size_bytes, user_assigned, locked, created_at, updated_at)
                    SELECT g.id, g.platform_id, ai.kind, '', '',
                           ai.doc_uri_or_path, 'import-esde', ai.size_bytes, 0, 0, ai.updated_at, ai.updated_at
                    FROM artwork_index ai JOIN games g ON g.artwork_key = ai.`key`
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE IF EXISTS artwork_index")
            }
        }

        // v27 — icon display modes + richer scrape metadata.
        //  • box_art_uri / physical_media_uri / box3d_uri: column-backed alternative XMB tiles
        //    (BOX_ART / PHYSICAL_MEDIA / BOX_3D kinds); icon_display_mode is the per-game
        //    override (null = follow the global setting).
        //  • players / age_rating / franchise / community_rating / release_date: ScreenScraper
        //    metadata already present in scrape responses, now persisted for Game Detail.
        //  • ICON records that actually hold ES-DE box art (source import-esde/relink) become
        //    BOX_ART in place — the file already sits in covers/, which BOX_ART now owns; the
        //    box_art_uri column is seeded from them and icon_uri cleared where it pointed at the
        //    reclassified file. Scrape/user/internal-migration ICON records are true 144:80
        //    icons; their FILES still live in covers/ and are relocated to pfp/icon0/ by the
        //    Kotlin one-shot (SAF moves can't run in SQL) — see IconCoversReclassifyWorker.
        val MIGRATION_26_27 = object : Migration(26, 27) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE games ADD COLUMN box_art_uri TEXT")
                db.execSQL("ALTER TABLE games ADD COLUMN physical_media_uri TEXT")
                db.execSQL("ALTER TABLE games ADD COLUMN box3d_uri TEXT")
                db.execSQL("ALTER TABLE games ADD COLUMN icon_display_mode TEXT")
                db.execSQL("ALTER TABLE games ADD COLUMN players TEXT")
                db.execSQL("ALTER TABLE games ADD COLUMN age_rating TEXT")
                db.execSQL("ALTER TABLE games ADD COLUMN franchise TEXT")
                db.execSQL("ALTER TABLE games ADD COLUMN community_rating REAL")
                db.execSQL("ALTER TABLE games ADD COLUMN release_date TEXT")

                // Imported ES-DE "covers" were stored as ICON before BOX_ART existed — they are
                // true box art. Reclassify in place (unique (game_id, artwork_type) can't clash:
                // BOX_ART rows can't exist before this migration).
                db.execSQL(
                    """
                    UPDATE artwork_records SET artwork_type = 'BOX_ART'
                    WHERE artwork_type = 'ICON' AND source IN ('import-esde', 'relink')
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE games SET box_art_uri = (
                        SELECT ar.document_uri FROM artwork_records ar
                        WHERE ar.game_id = games.id AND ar.artwork_type = 'BOX_ART'
                    )
                    WHERE box_art_uri IS NULL
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    UPDATE games SET icon_uri = NULL
                    WHERE icon_uri IS NOT NULL AND icon_uri = box_art_uri
                    """.trimIndent()
                )
                // Seed physical_media_uri from records imported before it was column-backed.
                db.execSQL(
                    """
                    UPDATE games SET physical_media_uri = (
                        SELECT ar.document_uri FROM artwork_records ar
                        WHERE ar.game_id = games.id AND ar.artwork_type = 'PHYSICAL_MEDIA'
                    )
                    WHERE physical_media_uri IS NULL
                    """.trimIndent()
                )
            }
        }

        // v28 — ScreenScraper media-URL cache. One jeuInfos response carries URLs for EVERY
        // artwork kind; caching the list (keyed by SS game id) lets later scrapes of
        // newly-enabled kinds and the Artwork Studio's browse grid skip the metadata call.
        // Pure cache — rebuildable, losing it costs one API call per game, never data.
        val MIGRATION_27_28 = object : Migration(27, 28) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS ss_media_cache (
                        ss_id INTEGER NOT NULL PRIMARY KEY,
                        medias_json TEXT NOT NULL,
                        fetched_at INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        // v29 — Artwork Studio pass 2. Adds provenance + one-previous-version + baked-crop
        // columns to artwork_records: origin_url (re-download for "Reset to Scraped Default"),
        // provider (file-info panel), prev_* (single "Restore Previous" backup under
        // pfp/versions/), crop_rect + has_original (lossless re-crop from pfp/originals/).
        // All additive and nullable/defaulted — existing rows keep working untouched.
        val MIGRATION_28_29 = object : Migration(28, 29) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE artwork_records ADD COLUMN origin_url TEXT")
                db.execSQL("ALTER TABLE artwork_records ADD COLUMN provider TEXT")
                db.execSQL("ALTER TABLE artwork_records ADD COLUMN prev_document_uri TEXT")
                db.execSQL("ALTER TABLE artwork_records ADD COLUMN prev_relative_path TEXT")
                db.execSQL("ALTER TABLE artwork_records ADD COLUMN prev_size_bytes INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE artwork_records ADD COLUMN crop_rect TEXT")
                db.execSQL("ALTER TABLE artwork_records ADD COLUMN has_original INTEGER NOT NULL DEFAULT 0")
            }
        }

        // v30 — Shiba Coins achievement system. Adds achievement_sets (one denormalized coin
        // summary + sync metadata per game/provider, for O(1) glance + wallet reads) and
        // achievements (one row per individual coin with tier, rarity and earned state). Both
        // cascade-delete with their game. Additive only; no existing data is touched.
        // See docs/shiba-coins-achievements-plan.md.
        val MIGRATION_29_30 = object : Migration(29, 30) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS achievement_sets (
                        game_id INTEGER NOT NULL,
                        provider TEXT NOT NULL,
                        provider_game_id TEXT NOT NULL,
                        bronze_total INTEGER NOT NULL,
                        silver_total INTEGER NOT NULL,
                        gold_total INTEGER NOT NULL,
                        bronze_earned INTEGER NOT NULL,
                        silver_earned INTEGER NOT NULL,
                        gold_earned INTEGER NOT NULL,
                        mastered INTEGER NOT NULL,
                        last_synced_at INTEGER,
                        PRIMARY KEY(game_id, provider),
                        FOREIGN KEY(game_id) REFERENCES games(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS achievements (
                        game_id INTEGER NOT NULL,
                        provider TEXT NOT NULL,
                        provider_achievement_id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL,
                        tier TEXT NOT NULL,
                        global_rarity REAL NOT NULL,
                        icon_url TEXT,
                        is_hidden INTEGER NOT NULL,
                        is_earned INTEGER NOT NULL,
                        earned_at INTEGER,
                        PRIMARY KEY(game_id, provider, provider_achievement_id),
                        FOREIGN KEY(game_id) REFERENCES games(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }
        }

        // v31 — provider-game links. Maps a library game to its identifier on an achievement
        // provider (RA game id / Steam appid) so a sync knows what to fetch, set automatically
        // (Steam title match) or by hand. Additive; cascade-deletes with the game.
        val MIGRATION_30_31 = object : Migration(30, 31) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS provider_game_links (
                        game_id INTEGER NOT NULL,
                        provider TEXT NOT NULL,
                        provider_game_id TEXT NOT NULL,
                        source TEXT NOT NULL,
                        resolved_at INTEGER NOT NULL,
                        PRIMARY KEY(game_id),
                        FOREIGN KEY(game_id) REFERENCES games(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }
        }

        val MIGRATION_31_32 = object : Migration(31, 32) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS achievement_match_notes (
                        game_id INTEGER NOT NULL,
                        reason TEXT NOT NULL,
                        checked_at INTEGER NOT NULL,
                        PRIMARY KEY(game_id),
                        FOREIGN KEY(game_id) REFERENCES games(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
            }
        }

        // v33 — account-keyed achievement storage. Library-keyed achievement_sets/achievements
        // become account_achievement_sets/account_achievements, keyed by (provider,
        // provider_game_id) so account-imported games without a library copy are first-class rows.
        // Library games reach their rows through provider_game_links, whose key widens to
        // (game_id, provider) so a STEAM and a LOCAL_STEAM link coexist on one game. Existing rows
        // copy across (titles joined from games; duplicates on the same provider identity merge by
        // construction). Account rows have no FK to games: they survive library deletion and
        // provider disconnect. See docs/account-achievements-plan.md.
        val MIGRATION_32_33 = object : Migration(32, 33) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS account_achievement_sets (
                        provider TEXT NOT NULL,
                        provider_game_id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        icon_url TEXT,
                        bronze_total INTEGER NOT NULL,
                        silver_total INTEGER NOT NULL,
                        gold_total INTEGER NOT NULL,
                        bronze_earned INTEGER NOT NULL,
                        silver_earned INTEGER NOT NULL,
                        gold_earned INTEGER NOT NULL,
                        mastered INTEGER NOT NULL,
                        last_synced_at INTEGER,
                        PRIMARY KEY(provider, provider_game_id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS account_achievements (
                        provider TEXT NOT NULL,
                        provider_game_id TEXT NOT NULL,
                        provider_achievement_id TEXT NOT NULL,
                        title TEXT NOT NULL,
                        description TEXT NOT NULL,
                        tier TEXT NOT NULL,
                        global_rarity REAL NOT NULL,
                        icon_url TEXT,
                        is_hidden INTEGER NOT NULL,
                        is_earned INTEGER NOT NULL,
                        earned_at INTEGER,
                        PRIMARY KEY(provider, provider_game_id, provider_achievement_id)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO account_achievement_sets
                        (provider, provider_game_id, title, icon_url,
                         bronze_total, silver_total, gold_total,
                         bronze_earned, silver_earned, gold_earned, mastered, last_synced_at)
                    SELECT s.provider, s.provider_game_id, COALESCE(g.title, ''), NULL,
                           s.bronze_total, s.silver_total, s.gold_total,
                           s.bronze_earned, s.silver_earned, s.gold_earned, s.mastered, s.last_synced_at
                    FROM achievement_sets s LEFT JOIN games g ON g.id = s.game_id
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT OR IGNORE INTO account_achievements
                        (provider, provider_game_id, provider_achievement_id, title, description,
                         tier, global_rarity, icon_url, is_hidden, is_earned, earned_at)
                    SELECT a.provider, s.provider_game_id, a.provider_achievement_id, a.title,
                           a.description, a.tier, a.global_rarity, a.icon_url, a.is_hidden,
                           a.is_earned, a.earned_at
                    FROM achievements a
                    JOIN achievement_sets s ON s.game_id = a.game_id AND s.provider = a.provider
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE achievements")
                db.execSQL("DROP TABLE achievement_sets")

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS provider_game_links_v2 (
                        game_id INTEGER NOT NULL,
                        provider TEXT NOT NULL,
                        provider_game_id TEXT NOT NULL,
                        source TEXT NOT NULL,
                        resolved_at INTEGER NOT NULL,
                        PRIMARY KEY(game_id, provider),
                        FOREIGN KEY(game_id) REFERENCES games(id) ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    INSERT INTO provider_game_links_v2 (game_id, provider, provider_game_id, source, resolved_at)
                    SELECT game_id, provider, provider_game_id, source, resolved_at FROM provider_game_links
                    """.trimIndent()
                )
                db.execSQL("DROP TABLE provider_game_links")
                db.execSQL("ALTER TABLE provider_game_links_v2 RENAME TO provider_game_links")
            }
        }
    }
}
