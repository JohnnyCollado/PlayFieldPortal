package com.playfieldportal.core.data.database

import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * v55 — the Xbox 360 platform default moves from the unconfirmed `emu.x360.mobile` to X360
 * Mobile's published id `emu.x360mobile.com`. A platform default that is not installed fails the
 * launch outright, so the stale seed must be rewritten; a default the user chose is left alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration54To55Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    @Test
    fun `the seeded X360 Mobile default moves to the published package id`() {
        helper.createDatabase(54).use { db -> db.execSQL(x360(preferred = "'emu.x360.mobile'")) }

        helper.runMigrationsAndValidate(55, listOf(PFPDatabase.MIGRATION_54_55)).use { db ->
            assertEquals("emu.x360mobile.com", db.preferredFor("x360"))
        }
    }

    @Test
    fun `a default the user changed is kept`() {
        helper.createDatabase(54).use { db -> db.execSQL(x360(preferred = "'aenu.ax360e'")) }

        helper.runMigrationsAndValidate(55, listOf(PFPDatabase.MIGRATION_54_55)).use { db ->
            assertEquals("aenu.ax360e", db.preferredFor("x360"))
        }
    }

    @Test
    fun `a cleared default stays cleared`() {
        helper.createDatabase(54).use { db -> db.execSQL(x360(preferred = "NULL")) }

        helper.runMigrationsAndValidate(55, listOf(PFPDatabase.MIGRATION_54_55)).use { db ->
            assertEquals(1, db.count("SELECT COUNT(*) FROM platforms WHERE id = 'x360' AND preferred_emulator_package IS NULL"))
        }
    }

    private fun androidx.sqlite.SQLiteConnection.preferredFor(id: String): String =
        singleRow("SELECT preferred_emulator_package FROM platforms WHERE id = '$id'") { it.getText(0) }

    private fun x360(preferred: String): String = """
        INSERT INTO platforms
            (id, name, short_name, icon_res, accent_color, is_pinned_to_bar, bar_position,
             preferred_emulator_package, rom_extensions)
        VALUES ('x360', 'Xbox 360', 'X360', 'ic_platform_xbox360', 4279270416, 0, -1,
                $preferred, 'iso,xex,zar,xbla')
    """.trimIndent()

    companion object {
        const val DB = "migration-55-test"
    }
}
