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

/** Validates the additive v34 Steam-import tables against the exported schemas. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration33To34Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PFPDatabase::class.java,
    )

    @Test
    fun `v34 adds the owned-games cache and memo without touching existing rows`() {
        helper.createDatabase(DB, 33).apply {
            execSQL(
                "INSERT INTO account_achievement_sets (provider, provider_game_id, title, " +
                    "bronze_total, silver_total, gold_total, bronze_earned, silver_earned, " +
                    "gold_earned, mastered, last_synced_at) " +
                    "VALUES ('STEAM', '440', 'TF2', 1, 0, 0, 1, 0, 0, 0, 1)",
            )
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 34, true, PFPDatabase.MIGRATION_33_34)

        db.query("SELECT COUNT(*) FROM account_achievement_sets").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM steam_owned_games").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM steam_no_achievements").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }

    private companion object {
        const val DB = "migration-34-test"
    }
}
