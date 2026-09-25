package com.playfieldportal.core.data.database

import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * v50 — `game_storefront_identities` (C23 T6).
 *
 * Two things are worth pinning. The table arrives empty, because every row in it is something the
 * resolver or the user establishes. And `games.storefront` / `games.storefront_game_id` are NOT
 * migrated into it: the import-captured pair and a resolved storefront identity are different
 * facts, and folding one into the other would make a later Steam import deduplicate against a game
 * it never installed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration49To50Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    @Test
    fun `the table is created empty and the games row is left exactly as it was`() {
        helper.createDatabase(49).use { db ->
            db.execSQL(game(1, "Portal 2", storefront = "STEAM", storefrontGameId = "620"))
        }

        helper.runMigrationsAndValidate(50, listOf(PFPDatabase.MIGRATION_49_50)).use { db ->
            assertEquals(0, db.count("SELECT COUNT(*) FROM game_storefront_identities"))
            // The captured pair is untouched — not copied, not cleared.
            assertEquals(
                "STEAM:620",
                db.singleRow("SELECT storefront, storefront_game_id FROM games WHERE id = 1") {
                    it.getText(0) + ":" + it.getText(1)
                },
            )
        }
    }

    @Test
    fun `a row survives a round trip and is scoped to its store`() {
        helper.createDatabase(49).use { db ->
            db.execSQL(game(1, "Cyberpunk 2077"))
        }

        helper.runMigrationsAndValidate(50, listOf(PFPDatabase.MIGRATION_49_50)).use { db ->
            db.execSQL(identity(gameId = 1, store = "STEAM", storeId = "1091500"))
            // The same game on a second store is a second ROW, not a second game (Phase 11).
            db.execSQL(identity(gameId = 1, store = "GOG", storeId = "1423049311"))

            assertEquals(2, db.count("SELECT COUNT(*) FROM game_storefront_identities WHERE game_id = 1"))
            assertEquals(
                "1091500",
                db.singleRow(
                    "SELECT store_id FROM game_storefront_identities WHERE game_id = 1 AND store = 'STEAM'"
                ) { it.getText(0) },
            )
        }
    }

    @Test
    fun `identities are cascade-deleted with their game`() {
        helper.createDatabase(49).use { db ->
            db.execSQL(game(1, "Portal 2"))
        }

        helper.runMigrationsAndValidate(50, listOf(PFPDatabase.MIGRATION_49_50)).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL(identity(gameId = 1, store = "STEAM", storeId = "620"))
            db.execSQL("DELETE FROM games WHERE id = 1")

            assertEquals(0, db.count("SELECT COUNT(*) FROM game_storefront_identities"))
        }
    }

    /** A minimal `games` row: every NOT NULL column with no default, and nothing else. */
    private fun game(
        id: Long,
        title: String,
        storefront: String? = null,
        storefrontGameId: String? = null,
    ): String {
        fun quoted(value: String?) = value?.let { "'" + it + "'" } ?: "NULL"
        return """
            INSERT INTO games (id, title, platform_id, is_disc_primary, is_favorite,
                               favorite_sort_order, total_play_time_millis, is_manual_entry,
                               created_at, content_type, is_missing, storefront, storefront_game_id)
            VALUES ($id, '$title', 'windows', 1, 0, 0, 0, 0, 0, 'GAME', 0,
                    ${quoted(storefront)}, ${quoted(storefrontGameId)})
        """.trimIndent()
    }

    private fun identity(gameId: Long, store: String, storeId: String) =
        """
        INSERT INTO game_storefront_identities
            (game_id, store, store_id, confidence, user_confirmed, linked_at)
        VALUES ($gameId, '$store', '$storeId', 'EXACT', 0, 1700000000000)
        """.trimIndent()

    companion object {
        const val DB = "migration-50-test"
    }
}
