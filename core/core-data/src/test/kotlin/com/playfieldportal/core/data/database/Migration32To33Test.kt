package com.playfieldportal.core.data.database

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Validates the v33 move to account-keyed achievement storage against the exported v32 schema:
 * library sets/coins land in the account tables with titles joined from games, duplicates on one
 * provider identity merge, orphan sets survive, and provider_game_links widens its key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration32To33Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    private fun SQLiteConnection.seedGame(id: Long, title: String) = execSQL(
        """
        INSERT INTO games (id, title, platform_id, is_favorite, favorite_sort_order,
                           total_play_time_millis, is_manual_entry, created_at, content_type)
        VALUES ($id, '$title', 'snes', 0, 0, 0, 0, 0, 'GAME')
        """.trimIndent(),
    )

    private fun SQLiteConnection.seedSet(
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

    private fun SQLiteConnection.seedCoin(gameId: Long, provider: String, achievementId: String) = execSQL(
        """
        INSERT INTO achievements (game_id, provider, provider_achievement_id, title, description,
                                  tier, global_rarity, icon_url, is_hidden, is_earned, earned_at)
        VALUES ($gameId, '$provider', '$achievementId', 'Coin', '', 'BRONZE', 30.0, NULL, 0, 1, 222)
        """.trimIndent(),
    )

    private fun SQLiteConnection.seedLink(gameId: Long, provider: String, providerGameId: String) = execSQL(
        "INSERT INTO provider_game_links (game_id, provider, provider_game_id, source, resolved_at) " +
            "VALUES ($gameId, '$provider', '$providerGameId', 'MANUAL', 0)",
    )

    @Test
    fun `library sets and coins move into the account tables with game titles`() {
        helper.createDatabase(32).use { db ->
            db.seedGame(1, "Chrono Trigger")
            db.seedSet(1, "RETRO_ACHIEVEMENTS", "319", bronzeEarned = 1)
            db.seedCoin(1, "RETRO_ACHIEVEMENTS", "77")
            db.seedLink(1, "RETRO_ACHIEVEMENTS", "319")
        }

        helper.runMigrationsAndValidate(33, listOf(PFPDatabase.MIGRATION_32_33)).use { db ->
            val sets = db.rows("SELECT provider, provider_game_id, title, bronze_earned FROM account_achievement_sets") {
                listOf(it.getText(0), it.getText(1), it.getText(2), it.getLong(3).toInt())
            }
            assertEquals(listOf(listOf("RETRO_ACHIEVEMENTS", "319", "Chrono Trigger", 1)), sets)

            db.singleRow(
                "SELECT provider_game_id, earned_at FROM account_achievements " +
                    "WHERE provider = 'RETRO_ACHIEVEMENTS' AND provider_achievement_id = '77'",
            ) {
                assertEquals("319", it.getText(0))
                assertEquals(222L, it.getLong(1))
            }
            db.singleRow("SELECT game_id, provider FROM provider_game_links") {
                assertEquals(1L, it.getLong(0))
                assertEquals("RETRO_ACHIEVEMENTS", it.getText(1))
            }
        }
    }

    @Test
    fun `two library games on one provider identity merge into a single account row`() {
        helper.createDatabase(32).use { db ->
            db.seedGame(1, "Half-Life 2")
            db.seedGame(2, "Half-Life 2 (copy)")
            db.seedSet(1, "STEAM", "220", bronzeEarned = 2)
            db.seedSet(2, "STEAM", "220", bronzeEarned = 1)
            db.seedCoin(1, "STEAM", "ACH_WIN")
            db.seedCoin(2, "STEAM", "ACH_WIN")
            db.seedLink(1, "STEAM", "220")
            db.seedLink(2, "STEAM", "220")
        }

        helper.runMigrationsAndValidate(33, listOf(PFPDatabase.MIGRATION_32_33)).use { db ->
            assertEquals(1, db.count("SELECT COUNT(*) FROM account_achievement_sets")) // dedupe by construction
            assertEquals(1, db.count("SELECT COUNT(*) FROM account_achievements"))
            assertEquals(2, db.count("SELECT COUNT(*) FROM provider_game_links")) // both games keep their link to the shared entry
        }
    }

    @Test
    fun `an orphan set with no game and no link still migrates`() {
        helper.createDatabase(32).use { db ->
            // A set whose game was unlinked after syncing: rows persist keyed by a game id
            // that has no link. The game itself exists (FK), but nothing points at the set.
            db.seedGame(9, "Formerly Linked")
            db.seedSet(9, "STEAM", "440", bronzeEarned = 1)
        }

        helper.runMigrationsAndValidate(33, listOf(PFPDatabase.MIGRATION_32_33)).use { db ->
            db.singleRow("SELECT title FROM account_achievement_sets WHERE provider_game_id = '440'") {
                assertEquals("Formerly Linked", it.getText(0))
            }
        }
    }

    private companion object {
        const val DB = "migration-test"
    }
}
