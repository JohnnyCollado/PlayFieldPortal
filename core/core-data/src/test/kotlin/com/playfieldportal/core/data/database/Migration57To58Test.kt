package com.playfieldportal.core.data.database

import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * v58 — PS2 learns `.cue`, the sheet of a cue+bin dump (issue #22).
 *
 * The seeder runs INSERT OR IGNORE, so a corrected default only reaches a fresh install; this
 * migration carries it to everyone else. Like the v49 ps3 backfill, both writes are guarded on the
 * value still being the old seed — a list the user edited is theirs, not the knowledge base's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration57To58Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    @Test
    fun `an untouched ps2 row and card gain cue, and other platforms are left alone`() {
        helper.createDatabase(57).use { db ->
            db.execSQL(platform("ps2", "iso,bin,chd"))
            db.execSQL(card("ps2", "iso,bin,chd"))
            // A neighbour with its own extensions must not be touched by a ps2 fix.
            db.execSQL(platform("psx", "cue,bin,iso,pbp,chd"))
            db.execSQL(card("psx", "cue,bin,iso,pbp,chd"))
        }

        helper.runMigrationsAndValidate(58, listOf(PFPDatabase.MIGRATION_57_58)).use { db ->
            assertEquals("iso,bin,chd,cue", db.platformExts("ps2"))
            assertEquals("iso,bin,chd,cue", db.cardExts("ps2"))
            assertEquals("cue,bin,iso,pbp,chd", db.platformExts("psx"))
            assertEquals("cue,bin,iso,pbp,chd", db.cardExts("psx"))
        }
    }

    @Test
    fun `a list the user trimmed survives the update`() {
        helper.createDatabase(57).use { db ->
            db.execSQL(platform("ps2", "iso,chd"))
            db.execSQL(card("ps2", "iso"))
        }

        helper.runMigrationsAndValidate(58, listOf(PFPDatabase.MIGRATION_57_58)).use { db ->
            assertEquals("iso,chd", db.platformExts("ps2"), "a user override outranks the knowledge base")
            assertEquals("iso", db.cardExts("ps2"))
        }
    }

    @Test
    fun `a card that already names cue is never written twice`() {
        helper.createDatabase(57).use { db ->
            db.execSQL(platform("ps2", "cue,iso,bin,chd"))
            db.execSQL(card("ps2", "cue,bin"))
        }

        helper.runMigrationsAndValidate(58, listOf(PFPDatabase.MIGRATION_57_58)).use { db ->
            assertEquals("cue,iso,bin,chd", db.platformExts("ps2"))
            assertEquals("cue,bin", db.cardExts("ps2"))
        }
    }

    private fun platform(id: String, exts: String) =
        """
        INSERT INTO platforms (id, name, short_name, icon_res, accent_color,
                               is_pinned_to_bar, bar_position, preferred_emulator_package,
                               rom_extensions)
        VALUES ('$id', '$id', '$id', NULL, 0, 0, -1, NULL, '$exts')
        """.trimIndent()

    private fun card(platformId: String, exts: String) =
        """
        INSERT INTO memory_cards (platform_id, display_name, enabled, pinned, sort_order,
                                  supported_extensions, scan_recursively, game_count)
        VALUES ('$platformId', '$platformId', 1, 0, 0, '$exts', 1, 0)
        """.trimIndent()

    private fun androidx.sqlite.SQLiteConnection.platformExts(id: String) =
        singleRow("SELECT rom_extensions FROM platforms WHERE id = '$id'") { it.getText(0) }

    private fun androidx.sqlite.SQLiteConnection.cardExts(platformId: String) =
        singleRow("SELECT supported_extensions FROM memory_cards WHERE platform_id = '$platformId'") {
            it.getText(0)
        }

    companion object {
        const val DB = "migration-58-test"
    }
}
