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

/**
 * Validates the additive v38 multi-disc columns (disc_set_key / disc_number / is_disc_primary)
 * against the exported schemas. The pre-existing curated row must survive untouched, and all three
 * new columns must default to their safe values (NULL / NULL / 0) — existing rows behave exactly
 * as before until a rescan populates them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration37To38Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PFPDatabase::class.java,
    )

    @Test
    fun `v38 adds the disc set columns without touching existing rows`() {
        // Seed a v37 database with one curated (favorited) game.
        helper.createDatabase(DB, 37).apply {
            execSQL(
                "INSERT INTO games (title, platform_id, is_favorite, favorite_sort_order, " +
                    "total_play_time_millis, is_manual_entry, created_at, content_type, is_missing) " +
                    "VALUES ('Chrono Trigger', 'snes', 1, 0, 0, 0, 0, 'GAME', 0)",
            )
            close()
        }

        // Run the migration and validate the result against the exported 38 schema.
        val db = helper.runMigrationsAndValidate(DB, 38, true, PFPDatabase.MIGRATION_37_38)

        db.query(
            "SELECT title, disc_set_key, disc_number, is_disc_primary FROM games",
        ).use {
            assertTrue(it.moveToFirst())
            // Non-destructive: the row and its curation survived the migration.
            assertEquals("Chrono Trigger", it.getString(0))
            // New columns get their safe defaults for the pre-existing row.
            assertTrue(it.isNull(1))          // disc_set_key defaults to NULL (not in a set yet)
            assertTrue(it.isNull(2))          // disc_number defaults to NULL
            assertEquals(0, it.getInt(3))     // is_disc_primary defaults to 0 (not primary)
        }
    }

    private companion object {
        const val DB = "migration-38-test"
    }
}
