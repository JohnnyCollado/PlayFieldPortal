package com.playfieldportal.core.data.database

import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * v53 — `games.is_disc_preferred`: the disc the user picked with Choose Disc, kept apart from
 * `is_disc_primary` so a scan can tell a pick from the primary it derived itself. No existing row
 * can be known to be a pick, so the migration must mark none and leave every primary where it is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration52To53Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    @Test
    fun `existing discs are not marked preferred and keep their primary`() {
        helper.createDatabase(52).use { db ->
            db.execSQL(disc(title = "Disc 1", number = 1, primary = true))
            db.execSQL(disc(title = "Disc 2", number = 2, primary = false))
        }

        helper.runMigrationsAndValidate(53, listOf(PFPDatabase.MIGRATION_52_53)).use { db ->
            assertEquals(0, db.count("SELECT COUNT(*) FROM games WHERE is_disc_preferred != 0"))
            assertEquals(
                "Disc 1",
                db.singleRow("SELECT title FROM games WHERE is_disc_primary = 1") { it.getText(0) },
            )
        }
    }

    @Test
    fun `a preferred disc can be written after the migration`() {
        helper.createDatabase(52).use { db ->
            db.execSQL(disc(title = "Disc 1", number = 1, primary = true))
        }

        helper.runMigrationsAndValidate(53, listOf(PFPDatabase.MIGRATION_52_53)).use { db ->
            db.execSQL("UPDATE games SET is_disc_preferred = 1 WHERE title = 'Disc 1'")

            assertEquals(1, db.count("SELECT COUNT(*) FROM games WHERE is_disc_preferred = 1"))
        }
    }

    /** A minimal `games` row in one disc set: every NOT NULL column without a default. */
    private fun disc(title: String, number: Int, primary: Boolean): String = """
        INSERT INTO games
            (title, platform_id, rom_path, disc_set_key, disc_number, is_disc_primary, is_favorite,
             favorite_sort_order, total_play_time_millis, content_type, is_missing, is_manual_entry,
             created_at)
        VALUES ('$title', 'psx', '/roms/psx/$title.cue', 'set-a', $number, ${if (primary) 1 else 0}, 0,
                0, 0, 'GAME', 0, 0, 1700000000000)
    """.trimIndent()

    companion object {
        const val DB = "migration-53-test"
    }
}
