package com.playfieldportal.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.playfieldportal.core.data.database.dao.AppOverrideDao
import com.playfieldportal.core.data.database.dao.CategoryDao
import com.playfieldportal.core.data.database.dao.CollectionDao
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.dao.LibrarySourceDao
import com.playfieldportal.core.data.database.dao.MemoryCardDao
import com.playfieldportal.core.data.database.dao.PlaySessionDao
import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.dao.ThemeDao
import com.playfieldportal.core.data.database.dao.UnmatchedRomDao
import com.playfieldportal.core.data.database.entity.AppOverrideEntity
import com.playfieldportal.core.data.database.entity.CategoryEntity
import com.playfieldportal.core.data.database.entity.CategoryItemEntity
import com.playfieldportal.core.data.database.entity.CollectionEntity
import com.playfieldportal.core.data.database.entity.CollectionGameEntity
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.LibrarySourceEntity
import com.playfieldportal.core.data.database.entity.MemoryCardEntity
import com.playfieldportal.core.data.database.entity.PlaySessionEntity
import com.playfieldportal.core.data.database.entity.PlatformEntity
import com.playfieldportal.core.data.database.entity.ThemeEntity
import com.playfieldportal.core.data.database.entity.UnmatchedRomEntity

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
    ],
    version = 13,
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
    }
}
