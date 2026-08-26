package com.playfieldportal.core.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration39To40Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PFPDatabase::class.java,
    )

    @Test
    fun `v40 adds the games region column as nullable`() {
        helper.createDatabase(DB, 39).apply {
            execSQL(
                "INSERT INTO games (title, platform_id, rom_path, disc_set_key, disc_number, is_disc_primary, " +
                    "is_favorite, favorite_sort_order, total_play_time_millis, content_type, is_missing, is_manual_entry, created_at) " +
                    "VALUES ('Parasite Eve II (Disc 1)', 'psx', '/roms/pe2-1.cue', NULL, NULL, 0, 0, 0, 0, 'GAME', 0, 0, 1)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 40, true, PFPDatabase.MIGRATION_39_40)

        // Existing rows keep region NULL (detected on the next scan that touches them).
        db.query("SELECT region FROM games WHERE rom_path = '/roms/pe2-1.cue'").use {
            assertTrue(it.moveToFirst())
            assertNull(it.getString(0))
        }
        // The column is writable with a detected region value.
        db.execSQL("UPDATE games SET region = 'NTSC_U' WHERE rom_path = '/roms/pe2-1.cue'")
        db.query("SELECT region FROM games WHERE rom_path = '/roms/pe2-1.cue'").use {
            assertTrue(it.moveToFirst())
            assertEquals("NTSC_U", it.getString(0))
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
        helper.createDatabase(DB, 39).apply {
            execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS index_games_one_disc_primary " +
                    "ON games (disc_set_key) " +
                    "WHERE disc_set_key IS NOT NULL AND is_disc_primary = 1"
            )
            execSQL(
                "INSERT INTO games (title, platform_id, rom_path, disc_set_key, disc_number, is_disc_primary, " +
                    "is_favorite, favorite_sort_order, total_play_time_millis, content_type, is_missing, is_manual_entry, created_at) " +
                    "VALUES ('Parasite Eve II (Disc 1)', 'psx', '/roms/pe2-1.cue', 'set-a', 1, 1, 0, 0, 0, 'GAME', 0, 0, 1)"
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 40, true, PFPDatabase.MIGRATION_39_40)

        db.query("PRAGMA index_list('games')").use {
            while (it.moveToNext()) {
                assertTrue(it.getString(1) != "index_games_one_disc_primary")
            }
        }
        db.query("SELECT region, disc_set_key FROM games WHERE rom_path = '/roms/pe2-1.cue'").use {
            assertTrue(it.moveToFirst())
            assertNull(it.getString(0))
            assertEquals("set-a", it.getString(1))
        }
    }

    private companion object {
        const val DB = "migration-40-test-v2"
    }
}
