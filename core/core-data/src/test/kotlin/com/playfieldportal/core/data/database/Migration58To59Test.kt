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
 * v59 — an app's own "is a game" choice, apart from the Android Games card (2026-11-24). Marking an
 * app as a game used to put it on that card; the choice now lives with the app's other overrides.
 * Existing rows keep everything and start with no choice: the Play Store's category still decides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration58To59Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    @Test
    fun `an existing override keeps its choices and starts with no game choice`() {
        helper.createDatabase(58).use { db ->
            db.execSQL(
                "INSERT INTO app_overrides (package_name, custom_label, is_hidden, customized) " +
                    "VALUES ('com.example.app', 'Renamed', 1, 1)",
            )
        }

        helper.runMigrationsAndValidate(59, listOf(PFPDatabase.MIGRATION_58_59)).use { db ->
            val row = db.singleRow(
                "SELECT custom_label, is_hidden, customized, game_override FROM app_overrides WHERE package_name = 'com.example.app'",
            ) { listOf(it.getText(0), it.getLong(1), it.getLong(2), if (it.isNull(3)) null else it.getLong(3)) }
            assertEquals("Renamed", row[0])
            assertEquals(1L, row[1])
            assertEquals(1L, row[2])
            assertNull(row[3], "no choice yet: the Play Store's category still decides")
        }
    }

    @Test
    fun `a choice either way can be stored`() {
        helper.createDatabase(58).use { }
        helper.runMigrationsAndValidate(59, listOf(PFPDatabase.MIGRATION_58_59)).use { db ->
            db.execSQL("INSERT INTO app_overrides (package_name, is_hidden, customized, game_override) VALUES ('a', 0, 0, 1)")
            db.execSQL("INSERT INTO app_overrides (package_name, is_hidden, customized, game_override) VALUES ('b', 0, 0, 0)")
            assertEquals(1, db.count("SELECT COUNT(*) FROM app_overrides WHERE game_override = 1"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM app_overrides WHERE game_override = 0"))
        }
    }

    private companion object {
        const val DB = "migration-58-59.db"
    }
}
