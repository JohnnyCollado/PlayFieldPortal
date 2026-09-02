package com.playfieldportal.core.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/** Validates the additive v34 Steam-import tables against the exported schemas. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration33To34Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    @Test
    fun `v34 adds the owned-games cache and memo without touching existing rows`() {
        helper.createDatabase(33).use { db ->
            db.execSQL(
                "INSERT INTO account_achievement_sets (provider, provider_game_id, title, " +
                    "bronze_total, silver_total, gold_total, bronze_earned, silver_earned, " +
                    "gold_earned, mastered, last_synced_at) " +
                    "VALUES ('STEAM', '440', 'TF2', 1, 0, 0, 1, 0, 0, 0, 1)",
            )
        }

        helper.runMigrationsAndValidate(34, listOf(PFPDatabase.MIGRATION_33_34)).use { db ->
            assertEquals(1, db.count("SELECT COUNT(*) FROM account_achievement_sets"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM steam_owned_games"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM steam_no_achievements"))
        }
    }

    private companion object {
        const val DB = "migration-34-test"
    }
}
