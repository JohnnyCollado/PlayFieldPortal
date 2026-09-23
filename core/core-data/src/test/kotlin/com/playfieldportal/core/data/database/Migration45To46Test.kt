package com.playfieldportal.core.data.database

import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * v46 — the confirmed-local-match ledger, per-provider sync state and the Steam metadata cache.
 *
 * The migration must preserve every existing achievement row and seed the ledger ONLY from live
 * provider links: an account-imported set has no link and must stay out of the ledger (and so out
 * of every tracked view), because a bare set cannot prove the game was ever on this device. A
 * link whose game is flagged missing is still a real former match, so it seeds as not present.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration45To46Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    private fun androidx.sqlite.SQLiteConnection.game(id: Long, title: String, missing: Boolean) = execSQL(
        "INSERT INTO games (id, title, platform_id, is_disc_primary, is_favorite, favorite_sort_order, " +
            "total_play_time_millis, is_manual_entry, created_at, content_type, is_missing) " +
            "VALUES ($id, '$title', 'snes', 1, 0, 0, 0, 0, 0, 'GAME', ${if (missing) 1 else 0})"
    )

    private fun androidx.sqlite.SQLiteConnection.link(gameId: Long, provider: String, id: String, at: Long) = execSQL(
        "INSERT INTO provider_game_links (game_id, provider, provider_game_id, source, resolved_at) " +
            "VALUES ($gameId, '$provider', '$id', 'MANUAL', $at)"
    )

    private fun androidx.sqlite.SQLiteConnection.set(provider: String, id: String, earned: Int, syncedAt: Long) = execSQL(
        "INSERT INTO account_achievement_sets (provider, provider_game_id, title, bronze_total, silver_total, " +
            "gold_total, bronze_earned, silver_earned, gold_earned, mastered, last_synced_at) " +
            "VALUES ('$provider', '$id', 'Set $id', $earned, 0, 0, $earned, 0, 0, 0, $syncedAt)"
    )

    private fun androidx.sqlite.SQLiteConnection.coin(provider: String, id: String, achievement: String) = execSQL(
        "INSERT INTO account_achievements (provider, provider_game_id, provider_achievement_id, title, " +
            "description, tier, global_rarity, is_hidden, is_earned, earned_at) " +
            "VALUES ('$provider', '$id', '$achievement', '$achievement', '', 'BRONZE', 10.0, 0, 1, 5)"
    )

    private fun seedV45() {
        helper.createDatabase(45).use { db ->
            db.game(1, "Chrono Trigger", missing = false)
            db.game(2, "Earthbound", missing = true)
            db.game(3, "Chrono Trigger (Copy)", missing = false)
            db.link(1, "RETRO_ACHIEVEMENTS", "319", at = 100)
            db.link(3, "RETRO_ACHIEVEMENTS", "319", at = 300)   // a second local copy, same identity
            db.link(2, "RETRO_ACHIEVEMENTS", "412", at = 200)   // matched, now missing
            db.set("RETRO_ACHIEVEMENTS", "319", earned = 5, syncedAt = 777)
            db.set("RETRO_ACHIEVEMENTS", "412", earned = 2, syncedAt = 888)
            db.set("STEAM", "999", earned = 40, syncedAt = 999)  // an old account import, no link
            db.coin("RETRO_ACHIEVEMENTS", "319", "a")
            db.coin("STEAM", "999", "b")
        }
    }

    @Test
    fun `every achievement row survives the upgrade`() {
        seedV45()

        helper.runMigrationsAndValidate(46, listOf(PFPDatabase.MIGRATION_45_46)).use { db ->
            assertEquals(3, db.count("SELECT COUNT(*) FROM account_achievement_sets"))
            assertEquals(2, db.count("SELECT COUNT(*) FROM account_achievements"))
            assertEquals(3, db.count("SELECT COUNT(*) FROM provider_game_links"))
        }
    }

    @Test
    fun `the ledger seeds from live links only, one row per provider identity`() {
        seedV45()

        helper.runMigrationsAndValidate(46, listOf(PFPDatabase.MIGRATION_45_46)).use { db ->
            val ids = db.rows(
                "SELECT provider || ':' || provider_game_id FROM achievement_tracked_identities ORDER BY 1"
            ) { it.getText(0) }
            assertEquals(listOf("RETRO_ACHIEVEMENTS:319", "RETRO_ACHIEVEMENTS:412"), ids)
            // The orphan import never enters the ledger.
            assertEquals(
                0,
                db.count("SELECT COUNT(*) FROM achievement_tracked_identities WHERE provider = 'STEAM'"),
            )
        }
    }

    @Test
    fun `a missing game seeds as a former match, and timestamps carry over`() {
        seedV45()

        helper.runMigrationsAndValidate(46, listOf(PFPDatabase.MIGRATION_45_46)).use { db ->
            db.singleRow(
                "SELECT is_present, first_matched_at, last_matched_at, last_detail_at, title " +
                    "FROM achievement_tracked_identities WHERE provider_game_id = '319'"
            ) {
                assertEquals(1L, it.getLong(0))
                assertEquals(100L, it.getLong(1))
                assertEquals(300L, it.getLong(2))
                assertEquals(777L, it.getLong(3))
                assertEquals("Chrono Trigger", it.getText(4))
            }
            db.singleRow(
                "SELECT is_present, last_detail_at FROM achievement_tracked_identities " +
                    "WHERE provider_game_id = '412'"
            ) {
                assertEquals(0L, it.getLong(0))
                assertEquals(888L, it.getLong(1))
            }
        }
    }

    @Test
    fun `sync state and metadata cache start empty`() {
        seedV45()

        helper.runMigrationsAndValidate(46, listOf(PFPDatabase.MIGRATION_45_46)).use { db ->
            assertEquals(0, db.count("SELECT COUNT(*) FROM achievement_provider_sync_state"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM achievement_metadata_cache"))
        }
    }

    private companion object {
        const val DB = "migration-46-test"
    }
}
