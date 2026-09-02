package com.playfieldportal.core.data.database

import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration39To40Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    @Test
    fun `v40 adds the games region column as nullable`() {
        helper.createDatabase(39).use { db ->
            db.execSQL(
                "INSERT INTO games (title, platform_id, rom_path, disc_set_key, disc_number, is_disc_primary, " +
                    "is_favorite, favorite_sort_order, total_play_time_millis, content_type, is_missing, is_manual_entry, created_at) " +
                    "VALUES ('Parasite Eve II (Disc 1)', 'psx', '/roms/pe2-1.cue', NULL, NULL, 0, 0, 0, 0, 'GAME', 0, 0, 1)"
            )
        }

        helper.runMigrationsAndValidate(40, listOf(PFPDatabase.MIGRATION_39_40)).use { db ->
            // Existing rows keep region NULL (detected on the next scan that touches them).
            db.singleRow("SELECT region FROM games WHERE rom_path = '/roms/pe2-1.cue'") {
                assertNull(if (it.isNull(0)) null else it.getText(0))
            }
            // The column is writable with a detected region value.
            db.execSQL("UPDATE games SET region = 'NTSC_U' WHERE rom_path = '/roms/pe2-1.cue'")
            db.singleRow("SELECT region FROM games WHERE rom_path = '/roms/pe2-1.cue'") {
                assertEquals("NTSC_U", it.getText(0))
            }
        }
    }

    @Test
    fun `v40 drops the legacy partial primary index from a broken v39 database`() {
        // Regression: the v39-era build created a partial unique index (index_games_one_disc_primary)
        // via raw SQL. Room cannot express partial indexes in its schema export, so its
        // post-migration validation refused to open such a database ("Migration didn't properly
        // handle: games") and the app crashed on every launch. Reproduce that exact state and
        // assert the migration heals it: validation passes (runMigrationsAndValidate throws
        // otherwise), the index is gone, and the region column is added.
        helper.createDatabase(39).use { db ->
            db.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_games_one_disc_primary " +
                    "ON games (disc_set_key) " +
                    "WHERE disc_set_key IS NOT NULL AND is_disc_primary = 1"
            )
            db.execSQL(
                "INSERT INTO games (title, platform_id, rom_path, disc_set_key, disc_number, is_disc_primary, " +
                    "is_favorite, favorite_sort_order, total_play_time_millis, content_type, is_missing, is_manual_entry, created_at) " +
                    "VALUES ('Parasite Eve II (Disc 1)', 'psx', '/roms/pe2-1.cue', 'set-a', 1, 1, 0, 0, 0, 'GAME', 0, 0, 1)"
            )
        }

        helper.runMigrationsAndValidate(40, listOf(PFPDatabase.MIGRATION_39_40)).use { db ->
            val indexes = db.rows("PRAGMA index_list('games')") { it.getText(1) }
            assertFalse("index_games_one_disc_primary" in indexes)

            db.singleRow("SELECT region, disc_set_key FROM games WHERE rom_path = '/roms/pe2-1.cue'") {
                assertNull(if (it.isNull(0)) null else it.getText(0))
                assertEquals("set-a", it.getText(1))
            }
        }
    }

    private companion object {
        const val DB = "migration-40-test-v2"
    }
}
