package com.playfieldportal.core.data.database

import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * v48 — `games.user_metadata_overrides` (C23 task T1).
 *
 * Purely additive, and the additive-ness is the whole point: the hand-set values live in a shadow
 * layer precisely so that no metadata column has to move. A migration that touched one while adding
 * the other would cost a user scraped metadata to gain the ability to correct it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration47To48Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    @Test
    fun `the column is added empty and every metadata column survives untouched`() {
        helper.createDatabase(47).use { db ->
            db.execSQL(
                """
                INSERT INTO games
                    (id, title, platform_id, rom_path, package_name, emulator_package, artwork_uri,
                     hero_uri, logo_uri, description, developer, publisher, release_year, genre,
                     steam_grid_db_id, scraped_title, user_title_override,
                     is_disc_primary, is_favorite, favorite_sort_order, total_play_time_millis,
                     is_manual_entry, created_at, content_type, is_missing)
                VALUES (7, 'crash', 'ps1', '/roms/ps1/crash.bin', NULL, NULL, NULL,
                        NULL, NULL, 'Scraped blurb', 'Naughty Dog', 'Sony', 1996, 'Platform',
                        NULL, 'Crash Bandicoot', 'Crash 1',
                        0, 0, 0, 0, 0, 100, 'GAME', 0)
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(48, listOf(PFPDatabase.MIGRATION_47_48)).use { db ->
            // A legacy row reads NULL, which MetadataOverrides.parse already treats as "no
            // overrides" — so nothing needs backfilling.
            val overrides = db.singleRow(
                "SELECT user_metadata_overrides FROM games WHERE id = 7"
            ) { if (it.isNull(0)) null else it.getText(0) }
            assertNull(overrides)

            val row = db.singleRow(
                """
                SELECT description, developer, publisher, release_year, genre,
                       scraped_title, user_title_override
                FROM games WHERE id = 7
                """.trimIndent()
            ) {
                listOf(
                    it.getText(0), it.getText(1), it.getText(2), it.getLong(3).toString(),
                    it.getText(4), it.getText(5), it.getText(6),
                )
            }
            assertEquals(
                listOf(
                    "Scraped blurb", "Naughty Dog", "Sony", "1996", "Platform",
                    "Crash Bandicoot", "Crash 1",
                ),
                row,
            )
        }
    }

    @Test
    fun `the new column holds a JSON map and the title override stays in its own column`() {
        helper.createDatabase(47).use { db ->
            db.execSQL(
                """
                INSERT INTO games
                    (id, title, platform_id, rom_path, package_name, emulator_package, artwork_uri,
                     hero_uri, logo_uri, description, developer, publisher, release_year, genre,
                     steam_grid_db_id, scraped_title, user_title_override,
                     is_disc_primary, is_favorite, favorite_sort_order, total_play_time_millis,
                     is_manual_entry, created_at, content_type, is_missing)
                VALUES (8, 'jak', 'ps2', '/roms/ps2/jak.iso', NULL, NULL, NULL,
                        NULL, NULL, NULL, 'Scraped Studio', NULL, 2001, NULL,
                        NULL, 'Jak and Daxter', NULL,
                        0, 0, 0, 0, 0, 100, 'GAME', 0)
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(48, listOf(PFPDatabase.MIGRATION_47_48)).use { db ->
            db.execSQL(
                "UPDATE games SET user_metadata_overrides = '{\"DEVELOPER\":\"My own studio\"}' " +
                    "WHERE id = 8"
            )
            // The two halves of one map, in the two places that own them: the achievement joins
            // read user_title_override in SQL, which is why TITLE never enters the blob.
            val stored = db.singleRow(
                "SELECT user_metadata_overrides, developer FROM games WHERE id = 8"
            ) { it.getText(0) to it.getText(1) }
            assertEquals("{\"DEVELOPER\":\"My own studio\"}" to "Scraped Studio", stored)
        }
    }

    companion object {
        const val DB = "migration-48-test"
    }
}
