package com.playfieldportal.core.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.playfieldportal.core.data.database.dao.CategoryDao
import com.playfieldportal.core.data.database.dao.GameDao
import com.playfieldportal.core.data.database.dao.LibrarySourceDao
import com.playfieldportal.core.data.database.dao.PlaySessionDao
import com.playfieldportal.core.data.database.dao.PlatformDao
import com.playfieldportal.core.data.database.dao.ThemeDao
import com.playfieldportal.core.data.database.dao.UnmatchedRomDao
import com.playfieldportal.core.data.database.entity.CategoryEntity
import com.playfieldportal.core.data.database.entity.CategoryItemEntity
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.LibrarySourceEntity
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
    ],
    version = 3,
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
    }
}
