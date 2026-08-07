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
 * Validates the additive v37 missing-ROM columns (is_missing / last_seen_at) against the exported
 * schemas. The pre-existing curated row must survive untouched, is_missing must default to 0
 * (not missing) and last_seen_at to NULL (never confirmed present yet).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration36To37Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PFPDatabase::class.java,
    )

    @Test
    fun `v37 adds is_missing and last_seen_at without touching existing rows`() {
        // Seed a v36 database with one curated (favorited) game.
        helper.createDatabase(DB, 36).apply {
            execSQL(
                "INSERT INTO games (title, platform_id, is_favorite, favorite_sort_order, " +
                    "total_play_time_millis, is_manual_entry, created_at, content_type) " +
                    "VALUES ('Chrono Trigger', 'snes', 1, 0, 0, 0, 0, 'GAME')",
            )
            close()
        }

        // Run the migration and validate the result against the exported 37 schema.
        val db = helper.runMigrationsAndValidate(DB, 37, true, PFPDatabase.MIGRATION_36_37)

        db.query(
            "SELECT title, is_favorite, is_missing, last_seen_at FROM games",
        ).use {
            assertTrue(it.moveToFirst())
            // Non-destructive: the row and its curation survived the migration.
            assertEquals("Chrono Trigger", it.getString(0))
            assertEquals(1, it.getInt(1))
            // New columns get their safe defaults for the pre-existing row.
            assertEquals(0, it.getInt(2))          // is_missing defaults to 0 (not missing)
            assertTrue(it.isNull(3))               // last_seen_at defaults to NULL
        }
    }

    private companion object {
        const val DB = "migration-37-test"
    }
}
