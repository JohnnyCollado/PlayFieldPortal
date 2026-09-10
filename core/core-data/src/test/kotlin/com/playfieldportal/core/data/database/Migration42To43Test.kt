package com.playfieldportal.core.data.database

import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

import kotlin.test.assertTrue

/**
 * v43 — Windows storefront identity, backfilled in place from `launch_intent_uri`.
 *
 * The backfill is the whole point of the migration: existing libraries must gain the identity
 * without a re-scan, and rows whose intent names no trustworthy store must be left null rather
 * than guessed at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration42To43Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    @Test
    fun `the intent-uri backfill produces the right store and id pairs`() {
        helper.createDatabase(41).use { db ->
            // GameNative — store named explicitly in game_source.
            insertPcGame(db, "Portal 2", GAME_NATIVE_STEAM)
            insertPcGame(db, "Cyberpunk 2077", GAME_NATIVE_GOG)
            // GameHub family — steamAppId is explicitly a Steam appid.
            insertPcGame(db, "Hades", GAME_HUB_STEAM)
        }

        helper.runMigrationsAndValidate(
            43, listOf(PFPDatabase.MIGRATION_41_42, PFPDatabase.MIGRATION_42_43),
        ).use { db ->
            val pairs = db.rows(
                "SELECT title, storefront, storefront_game_id FROM games ORDER BY title"
            ) { Triple(it.getText(0), it.getText(1), it.getText(2)) }

            assertEquals(
                listOf(
                    Triple("Cyberpunk 2077", "GOG", "1423049311"),
                    Triple("Hades", "STEAM", "1145360"),
                    Triple("Portal 2", "STEAM", "620"),
                ),
                pairs,
            )
        }
    }

    @Test
    fun `an intent with no trustworthy store identity is left null`() {
        helper.createDatabase(41).use { db ->
            // Winlator: a shortcut path into a Wine prefix — no store, no app id.
            insertPcGame(db, "Some EXE", WINLATOR_DESKTOP)
            // GameHub's localGameId is the launcher's internal id, never a storefront id.
            insertPcGame(db, "Local Game", GAME_HUB_LOCAL)
            // A ROM game has no launch intent at all.
            db.execSQL(
                "INSERT INTO games (title, platform_id, rom_path, is_favorite, favorite_sort_order, " +
                    "total_play_time_millis, content_type, is_missing, is_disc_primary, " +
                    "is_manual_entry, created_at) " +
                    "VALUES ('Crash', 'psx', '/roms/crash.bin', 0, 0, 0, 'GAME', 0, 0, 0, 1)"
            )
        }

        helper.runMigrationsAndValidate(
            43, listOf(PFPDatabase.MIGRATION_41_42, PFPDatabase.MIGRATION_42_43),
        ).use { db ->
            assertEquals(3, db.count("SELECT COUNT(*) FROM games"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM games WHERE storefront IS NOT NULL"))
            db.singleRow("SELECT title FROM games WHERE title = 'Crash'") {
                assertEquals("Crash", it.getText(0))
            }
        }
    }

    @Test
    fun `the same app id on two different stores is not a collision`() {
        helper.createDatabase(41).use { _ -> }

        helper.runMigrationsAndValidate(
            43, listOf(PFPDatabase.MIGRATION_41_42, PFPDatabase.MIGRATION_42_43),
        ).use { db ->
            insertStoreGame(db, "Steam 620", "STEAM", "620")
            insertStoreGame(db, "GOG 620", "GOG", "620")

            assertEquals(1, db.count("SELECT COUNT(*) FROM games WHERE storefront = 'STEAM' AND storefront_game_id = '620'"))
            assertEquals(1, db.count("SELECT COUNT(*) FROM games WHERE storefront = 'GOG' AND storefront_game_id = '620'"))
            // The index exists and is on the PAIR, not on the id alone.
            val hasIndex = db.rows("PRAGMA index_list('games')") { it.getText(1) }
                .any { it == "index_games_storefront_storefront_game_id" }
            assertTrue(hasIndex, "the (storefront, storefront_game_id) index is missing")
        }
    }

    @Test
    fun `existing game data is untouched by the migration`() {
        helper.createDatabase(41).use { db ->
            insertPcGame(db, "Portal 2", GAME_NATIVE_STEAM)
        }

        helper.runMigrationsAndValidate(
            43, listOf(PFPDatabase.MIGRATION_41_42, PFPDatabase.MIGRATION_42_43),
        ).use { db ->
            db.singleRow("SELECT title, platform_id, package_name, launch_intent_uri FROM games") {
                assertEquals("Portal 2", it.getText(0))
                assertEquals("windows", it.getText(1))
                assertEquals("app.gamenative", it.getText(2))
                assertEquals(GAME_NATIVE_STEAM, it.getText(3))
            }
            db.singleRow("SELECT storefront, storefront_game_id FROM games") {
                assertEquals("STEAM", it.getText(0))
                assertEquals("620", it.getText(1))
            }
        }
    }

    private companion object {
        const val DB = "migration-43-test"

        // Intent.toUri(URI_INTENT_SCHEME) output, as PcLauncherAdapter builds it.
        const val GAME_NATIVE_STEAM =
            "intent:#Intent;component=app.gamenative/app.gamenative.MainActivity;" +
                "action=app.gamenative.LAUNCH_GAME;launchFlags=0x10000000;" +
                "i.app_id=620;S.game_source=STEAM;B.autoStartGame=true;end"

        const val GAME_NATIVE_GOG =
            "intent:#Intent;component=app.gamenative/app.gamenative.MainActivity;" +
                "action=app.gamenative.LAUNCH_GAME;i.app_id=1423049311;S.game_source=GOG;end"

        const val GAME_HUB_STEAM =
            "intent:#Intent;component=gamehub.lite/com.xiaoji.egggame.DeepLinkActivity;" +
                "action=gamehub.lite.LAUNCH_GAME;S.steamAppId=1145360;B.autoStartGame=true;end"

        const val GAME_HUB_LOCAL =
            "intent:#Intent;component=gamehub.lite/com.xiaoji.egggame.DeepLinkActivity;" +
                "action=gamehub.lite.LAUNCH_GAME;S.localGameId=local_9f2c;B.autoStartGame=true;end"

        const val WINLATOR_DESKTOP =
            "intent:#Intent;component=com.winlator/com.winlator.MainActivity;" +
                "S.shortcut_path=%2Fstorage%2Femulated%2F0%2FDownload%2Fgame.desktop;end"

        fun insertPcGame(db: androidx.sqlite.SQLiteConnection, title: String, intentUri: String) = db.execSQL(
            "INSERT INTO games (title, platform_id, package_name, launch_intent_uri, is_favorite, " +
                "favorite_sort_order, total_play_time_millis, content_type, is_missing, " +
                "is_disc_primary, is_manual_entry, created_at) " +
                "VALUES ('$title', 'windows', 'app.gamenative', '$intentUri', 0, 0, 0, 'GAME', 0, 0, 1, 1)"
        )

        fun insertStoreGame(
            db: androidx.sqlite.SQLiteConnection,
            title: String,
            storefront: String,
            storeId: String,
        ) = db.execSQL(
            "INSERT INTO games (title, platform_id, storefront, storefront_game_id, is_favorite, " +
                "favorite_sort_order, total_play_time_millis, content_type, is_missing, " +
                "is_disc_primary, is_manual_entry, created_at) " +
                "VALUES ('$title', 'windows', '$storefront', '$storeId', 0, 0, 0, 'GAME', 0, 0, 1, 1)"
        )
    }
}
