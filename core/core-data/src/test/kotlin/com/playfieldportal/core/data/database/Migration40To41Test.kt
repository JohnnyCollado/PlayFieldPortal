package com.playfieldportal.core.data.database

import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration40To41Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    @Test
    fun `v41 creates the empty launch_outcomes table and preserves games`() {
        // A v40 database with an existing game row — the migration must be purely additive.
        helper.createDatabase(40).use { db ->
            db.execSQL(
                "INSERT INTO games (title, platform_id, rom_path, is_favorite, favorite_sort_order, " +
                    "total_play_time_millis, content_type, is_missing, is_disc_primary, is_manual_entry, created_at) " +
                    "VALUES ('Crash Bandicoot', 'psx', '/roms/crash.bin', 0, 0, 0, 'GAME', 0, 0, 0, 1)"
            )
        }

        helper.runMigrationsAndValidate(41, listOf(PFPDatabase.MIGRATION_40_41)).use { db ->
            // The game survives the migration untouched.
            db.singleRow("SELECT title FROM games WHERE rom_path = '/roms/crash.bin'") {
                assertEquals("Crash Bandicoot", it.getText(0))
            }
            // The new log table exists and is empty.
            assertEquals(0, db.count("SELECT COUNT(*) FROM launch_outcomes"))

            // A settled launch outcome round-trips through the schema.
            db.execSQL(
                "INSERT INTO launch_outcomes (game_id, game_title, platform_id, emulator_id, " +
                    "emulator_name, core_path, core_name, source, outcome, failure_reason, " +
                    "launched_at_ms, returned_at_ms) " +
                    "VALUES (1, 'Crash Bandicoot', 'psx', 'duckstation', 'DuckStation', NULL, NULL, " +
                    "'PLATFORM_DEFAULT', 'INTENT_FAILED', 'Emulator not found. Is it installed?', 1000, NULL)"
            )
            db.singleRow(
                "SELECT outcome, failure_reason, source FROM launch_outcomes WHERE game_id = 1"
            ) {
                assertEquals("INTENT_FAILED", it.getText(0))
                assertEquals("Emulator not found. Is it installed?", it.getText(1))
                assertEquals("PLATFORM_DEFAULT", it.getText(2))
            }
        }
    }

    @Test
    fun `v41 outcome rows are queryable newest-first per game`() {
        helper.createDatabase(40).use { _ -> }

        helper.runMigrationsAndValidate(41, listOf(PFPDatabase.MIGRATION_40_41)).use { db ->
            db.execSQL(
                "INSERT INTO launch_outcomes (game_id, game_title, platform_id, outcome, launched_at_ms) " +
                    "VALUES (1, 'A', 'psx', 'SUCCEEDED', 3000)"
            )
            db.execSQL(
                "INSERT INTO launch_outcomes (game_id, game_title, platform_id, outcome, launched_at_ms) " +
                    "VALUES (1, 'A', 'psx', 'NEVER_FOREGROUNDED', 1000)"
            )
            db.execSQL(
                "INSERT INTO launch_outcomes (game_id, game_title, platform_id, outcome, launched_at_ms) " +
                    "VALUES (2, 'B', 'snes', 'SUCCEEDED', 2000)"
            )

            val outcomes = db.rows("SELECT outcome, launched_at_ms FROM launch_outcomes " +
                "WHERE game_id = 1 ORDER BY launched_at_ms DESC") { stmt ->
                stmt.getText(0) to stmt.getLong(1)
            }
            assertEquals(listOf("SUCCEEDED" to 3000L, "NEVER_FOREGROUNDED" to 1000L), outcomes)
            assertTrue(outcomes.none { it.first == "SUCCEEDED" && it.second == 2000L }, "game 2 row leaked")
        }
    }

    private companion object {
        const val DB = "migration-41-test"
    }
}
