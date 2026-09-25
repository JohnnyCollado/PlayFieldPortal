package com.playfieldportal.core.data.database

import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * v49 — backfills `ps3`'s ROM extensions into libraries that already exist.
 *
 * The seeder runs INSERT OR IGNORE, so a corrected default only ever reaches a fresh install. This
 * migration is what carries a knowledge-base fix to everyone else — and the whole point of it is
 * that it stops at anything the user has set themselves.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration48To49Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    @Test
    fun `an untouched ps3 row and card are filled, and other platforms are left alone`() {
        helper.createDatabase(48).use { db ->
            db.execSQL(platform("ps3", ""))
            db.execSQL(card("ps3", ""))
            // A neighbour that already had extensions must not be touched by a ps3 fix.
            db.execSQL(platform("ps2", "iso,bin,chd"))
            db.execSQL(card("ps2", "iso,bin,chd"))
        }

        helper.runMigrationsAndValidate(49, listOf(PFPDatabase.MIGRATION_48_49)).use { db ->
            assertEquals("iso,pkg,ps3dir", db.platformExts("ps3"))
            assertEquals("iso,pkg,ps3dir", db.cardExts("ps3"))
            assertEquals("iso,bin,chd", db.platformExts("ps2"))
            assertEquals("iso,bin,chd", db.cardExts("ps2"))
        }
    }

    @Test
    fun `extensions the user typed themselves survive the update`() {
        helper.createDatabase(48).use { db ->
            // Anything non-empty is a value the user set: the seed was empty.
            db.execSQL(platform("ps3", "iso"))
            db.execSQL(card("ps3", "pkg,jb"))
        }

        helper.runMigrationsAndValidate(49, listOf(PFPDatabase.MIGRATION_48_49)).use { db ->
            assertEquals("iso", db.platformExts("ps3"), "a user override outranks the knowledge base")
            assertEquals("pkg,jb", db.cardExts("ps3"))
        }
    }

    @Test
    fun `a whitespace-only list counts as empty, not as an override`() {
        helper.createDatabase(48).use { db ->
            db.execSQL(platform("ps3", "   "))
            db.execSQL(card("ps3", " "))
        }

        helper.runMigrationsAndValidate(49, listOf(PFPDatabase.MIGRATION_48_49)).use { db ->
            // The scanner splits on commas and drops blanks, so "   " scans nothing — treating it
            // as an override would leave the user just as stuck as an empty string did.
            assertEquals("iso,pkg,ps3dir", db.platformExts("ps3"))
            assertEquals("iso,pkg,ps3dir", db.cardExts("ps3"))
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
        const val DB = "migration-49-test"
    }
}
