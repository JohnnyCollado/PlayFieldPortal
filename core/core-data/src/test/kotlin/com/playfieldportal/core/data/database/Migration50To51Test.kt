package com.playfieldportal.core.data.database

import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * v51 — `local_steam_folders` (folder-picked Local Steam matching).
 *
 * Two things are worth pinning. The table arrives EMPTY: seeding it would mean running the very deep
 * SAF tree walk it exists to retire, at migration time, against grants that may no longer be held.
 * And an existing LOCAL_STEAM provider link is left exactly as it was — the registry records where a
 * folder is, not whether a game is tracked, so a migration that touched links would be conflating
 * two different facts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration50To51Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    @Test
    fun `the table is created empty and existing local steam links are left alone`() {
        helper.createDatabase(50).use { db ->
            db.execSQL(game(1, "Marvel's Spider-Man"))
            db.execSQL(link(gameId = 1, provider = "LOCAL_STEAM", providerGameId = "1817070"))
        }

        helper.runMigrationsAndValidate(51, listOf(PFPDatabase.MIGRATION_50_51)).use { db ->
            assertEquals(0, db.count("SELECT COUNT(*) FROM local_steam_folders"))
            // The link keeps resolving through the fallback scan until the folder is re-picked.
            assertEquals(
                "1817070",
                db.singleRow(
                    "SELECT provider_game_id FROM provider_game_links WHERE game_id = 1 AND provider = 'LOCAL_STEAM'"
                ) { it.getText(0) },
            )
        }
    }

    @Test
    fun `a registered folder survives a round trip and is keyed by its app id`() {
        helper.createDatabase(50).use { }

        helper.runMigrationsAndValidate(51, listOf(PFPDatabase.MIGRATION_50_51)).use { db ->
            db.execSQL(folder(appId = "1817070", folderName = "Marvel's Spider-Man", source = "MARKER"))

            assertEquals(1, db.count("SELECT COUNT(*) FROM local_steam_folders"))
            assertEquals(
                "MARKER",
                db.singleRow("SELECT appid_source FROM local_steam_folders WHERE app_id = '1817070'") {
                    it.getText(0)
                },
            )

            // Re-picking the same game REPLACES its row rather than adding a second: one app id is
            // one folder, which is what makes findByAppId a primary-key read.
            db.execSQL(folder(appId = "1817070", folderName = "Spider-Man Remastered", source = "TITLE_MATCH"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM local_steam_folders"))
            assertEquals(
                "TITLE_MATCH",
                db.singleRow("SELECT appid_source FROM local_steam_folders WHERE app_id = '1817070'") {
                    it.getText(0)
                },
            )
        }
    }

    @Test
    fun `a folder with no progress file and no schema is a valid row`() {
        helper.createDatabase(50).use { }

        helper.runMigrationsAndValidate(51, listOf(PFPDatabase.MIGRATION_50_51)).use { db ->
            // "Not played yet, no kit yet" has to be storable: it is the state a freshly picked
            // folder is in, and the state Link Only leaves it in.
            db.execSQL(
                """
                INSERT INTO local_steam_folders
                    (app_id, folder_name, tree_uri, folder_doc_id, settings_dir_doc_id,
                     settings_parent_doc_id, progress_doc_id, has_schema, appid_source, last_seen_at)
                VALUES ('620', 'Portal 2', 'content://tree/games', 'games/Portal 2', NULL,
                        'games/Portal 2', NULL, 0, 'TITLE_MATCH', 1700000000000)
                """.trimIndent()
            )
            assertEquals(1, db.count("SELECT COUNT(*) FROM local_steam_folders WHERE progress_doc_id IS NULL"))
        }
    }

    /**
     * A minimal `games` row: every NOT NULL column with no default, and nothing else.
     *
     * [title] is escaped, because half the real titles in this feature's fixtures have an apostrophe
     * in them and a test that breaks on one teaches nothing.
     */
    private fun game(id: Long, title: String): String = """
        INSERT INTO games (id, title, platform_id, is_disc_primary, is_favorite,
                           favorite_sort_order, total_play_time_millis, is_manual_entry,
                           created_at, content_type, is_missing)
        VALUES ($id, '${title.replace("'", "''")}', 'windows', 1, 0, 0, 0, 0, 0, 'GAME', 0)
    """.trimIndent()

    private fun link(gameId: Long, provider: String, providerGameId: String) = """
        INSERT INTO provider_game_links (game_id, provider, provider_game_id, source, resolved_at)
        VALUES ($gameId, '$provider', '$providerGameId', 'MANUAL', 1700000000000)
    """.trimIndent()

    /** A registered folder row. [folderName] is escaped for the same reason [game]'s title is. */
    private fun folder(appId: String, folderName: String, source: String): String {
        val name = folderName.replace("'", "''")
        return """
            INSERT OR REPLACE INTO local_steam_folders
                (app_id, folder_name, tree_uri, folder_doc_id, settings_dir_doc_id,
                 settings_parent_doc_id, progress_doc_id, has_schema, appid_source, last_seen_at)
            VALUES ('$appId', '$name', 'content://tree/games', 'games/$name',
                    'games/$name/steam_settings', 'games/$name',
                    'games/$name/saves/$appId/achievements.json', 1, '$source', 1700000000000)
        """.trimIndent()
    }

    companion object {
        const val DB = "migration-51-test"
    }
}
