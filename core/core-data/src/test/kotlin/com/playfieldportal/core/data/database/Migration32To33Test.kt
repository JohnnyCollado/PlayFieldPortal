package com.playfieldportal.core.data.database

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Validates the v33 move to account-keyed achievement storage against the exported v32 schema:
 * library sets/coins land in the account tables with titles joined from games, duplicates on one
 * provider identity merge, orphan sets survive, and provider_game_links widens its key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration32To33Test {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        PFPDatabase::class.java,
    )

    private fun SupportSQLiteDatabase.seedGame(id: Long, title: String) = execSQL(
        """
        INSERT INTO games (id, title, platform_id, is_favorite, favorite_sort_order,
                           total_play_time_millis, is_manual_entry, created_at, content_type)
        VALUES ($id, '$title', 'snes', 0, 0, 0, 0, 0, 'GAME')
        """.trimIndent(),
    )

    private fun SupportSQLiteDatabase.seedSet(
        gameId: Long,
        provider: String,
        providerGameId: String,
        bronzeEarned: Int,
    ) = execSQL(
        """
        INSERT INTO achievement_sets (game_id, provider, provider_game_id,
                                      bronze_total, silver_total, gold_total,
                                      bronze_earned, silver_earned, gold_earned,
                                      mastered, last_synced_at)
        VALUES ($gameId, '$provider', '$providerGameId', 2, 0, 0, $bronzeEarned, 0, 0, 0, 111)
        """.trimIndent(),
    )

    private fun SupportSQLiteDatabase.seedCoin(gameId: Long, provider: String, achievementId: String) = execSQL(
        """
        INSERT INTO achievements (game_id, provider, provider_achievement_id, title, description,
                                  tier, global_rarity, icon_url, is_hidden, is_earned, earned_at)
        VALUES ($gameId, '$provider', '$achievementId', 'Coin', '', 'BRONZE', 30.0, NULL, 0, 1, 222)
        """.trimIndent(),
    )

    private fun SupportSQLiteDatabase.seedLink(gameId: Long, provider: String, providerGameId: String) = execSQL(
        "INSERT INTO provider_game_links (game_id, provider, provider_game_id, source, resolved_at) " +
            "VALUES ($gameId, '$provider', '$providerGameId', 'MANUAL', 0)",
    )

    @Test
    fun `library sets and coins move into the account tables with game titles`() {
        helper.createDatabase(DB, 32).apply {
            seedGame(1, "Chrono Trigger")
            seedSet(1, "RETRO_ACHIEVEMENTS", "319", bronzeEarned = 1)
            seedCoin(1, "RETRO_ACHIEVEMENTS", "77")
            seedLink(1, "RETRO_ACHIEVEMENTS", "319")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 33, true, PFPDatabase.MIGRATION_32_33)

        db.query("SELECT provider, provider_game_id, title, bronze_earned FROM account_achievement_sets").use {
            assertTrue(it.moveToFirst())
            assertEquals("RETRO_ACHIEVEMENTS", it.getString(0))
            assertEquals("319", it.getString(1))
            assertEquals("Chrono Trigger", it.getString(2))
            assertEquals(1, it.getInt(3))
            assertFalse(it.moveToNext())
        }
        db.query(
            "SELECT provider_game_id, earned_at FROM account_achievements " +
                "WHERE provider = 'RETRO_ACHIEVEMENTS' AND provider_achievement_id = '77'",
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals("319", it.getString(0))
            assertEquals(222L, it.getLong(1))
        }
        db.query("SELECT game_id, provider FROM provider_game_links").use {
            assertTrue(it.moveToFirst())
            assertEquals(1L, it.getLong(0))
            assertEquals("RETRO_ACHIEVEMENTS", it.getString(1))
        }
    }

    @Test
    fun `two library games on one provider identity merge into a single account row`() {
        helper.createDatabase(DB, 32).apply {
            seedGame(1, "Half-Life 2")
            seedGame(2, "Half-Life 2 (copy)")
            seedSet(1, "STEAM", "220", bronzeEarned = 2)
            seedSet(2, "STEAM", "220", bronzeEarned = 1)
            seedCoin(1, "STEAM", "ACH_WIN")
            seedCoin(2, "STEAM", "ACH_WIN")
            seedLink(1, "STEAM", "220")
            seedLink(2, "STEAM", "220")
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 33, true, PFPDatabase.MIGRATION_32_33)

        db.query("SELECT COUNT(*) FROM account_achievement_sets").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0)) // dedupe by construction
        }
        db.query("SELECT COUNT(*) FROM account_achievements").use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
        }
        db.query("SELECT COUNT(*) FROM provider_game_links").use {
            assertTrue(it.moveToFirst())
            assertEquals(2, it.getInt(0)) // both games keep their link to the shared entry
        }
    }

    @Test
    fun `an orphan set with no game and no link still migrates`() {
        helper.createDatabase(DB, 32).apply {
            // A set whose game was unlinked after syncing: rows persist keyed by a game id
            // that has no link. The game itself exists (FK), but nothing points at the set.
            seedGame(9, "Formerly Linked")
            seedSet(9, "STEAM", "440", bronzeEarned = 1)
            close()
        }

        val db = helper.runMigrationsAndValidate(DB, 33, true, PFPDatabase.MIGRATION_32_33)

        db.query("SELECT title FROM account_achievement_sets WHERE provider_game_id = '440'").use {
            assertTrue(it.moveToFirst())
            assertEquals("Formerly Linked", it.getString(0))
        }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
