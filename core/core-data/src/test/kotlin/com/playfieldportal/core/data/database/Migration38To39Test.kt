package com.playfieldportal.core.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration38To39Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PFPDatabase::class.java,
    )

    @Test
    fun `v39 repairs duplicate primaries with m3u and disc ordering`() {
        helper.createDatabase(DB, 38).apply {
            // The migration itself owns this partial index; remove it defensively when a test
            // runner reuses a schema artifact produced by the current database version.
            execSQL("DROP INDEX IF EXISTS index_games_one_disc_primary")
            val suffix = System.nanoTime()
            val columns = "title, platform_id, rom_path, disc_set_key, disc_number, is_disc_primary, " +
                "is_favorite, favorite_sort_order, total_play_time_millis, content_type, is_missing, is_manual_entry, created_at"
            fun insert(title: String, path: String, key: String, number: String, created: Int) {
                execSQL("INSERT INTO games ($columns) VALUES ('$title', 'psx', '$path', '$key', $number, 1, 0, 0, 0, 'GAME', 0, 0, $created)")
            }
            insert("Disc 2", "/roms/disc2-$suffix.cue", "set-a", "2", 1)
            insert("Playlist", "/roms/game-$suffix.m3u", "set-a", "NULL", 2)
            insert("Disc 1", "/roms/disc1-$suffix.cue", "set-a", "1", 3)
            insert("Other", "/roms/other-$suffix.cue", "set-b", "1", 4)
            insert("Other 2", "/roms/other2-$suffix.cue", "set-b", "2", 5)
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 39, true, PFPDatabase.MIGRATION_38_39)

        db.query("SELECT disc_number FROM games WHERE disc_set_key = 'set-a' AND is_disc_primary = 1").use {
            assertTrue(it.moveToFirst())
            assertTrue(it.isNull(0))
            assertEquals(1, it.count)
        }
        db.query("SELECT disc_number FROM games WHERE disc_set_key = 'set-b' AND is_disc_primary = 1").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
            assertEquals(1, it.count)
        }

        // The partial index permits multiple non-primary rows with the same set key.
        db.execSQL("INSERT INTO games (title, platform_id, rom_path, disc_set_key, disc_number, is_disc_primary, is_favorite, favorite_sort_order, total_play_time_millis, content_type, is_missing, is_manual_entry, created_at) VALUES ('Disc 3', 'psx', '/roms/disc3.cue', 'set-a', 3, 0, 0, 0, 0, 'GAME', 0, 0, 6)")
    }

    private companion object {
        const val DB = "migration-39-test-v7"
    }
}
