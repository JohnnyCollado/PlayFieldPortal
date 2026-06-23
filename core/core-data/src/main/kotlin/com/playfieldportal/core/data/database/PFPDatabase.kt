package com.playfieldportal.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.playfieldportal.core.data.database.dao.AppOverrideDao
import com.playfieldportal.core.data.database.dao.CategoryDao
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
    ],
    version = 7,
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
    }
}
