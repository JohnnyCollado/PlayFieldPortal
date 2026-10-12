package com.playfieldportal.core.data.database

import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * v57 -- a Windows game can carry a user label (Steam / Non-Steam / Local). Every existing game
 * starts unlabelled (Auto), so it behaves exactly as before.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration56To57Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    @Test
    fun `existing games start unlabelled`() {
        helper.createDatabase(56).use { db ->
            db.execSQL(
                """
                INSERT INTO games (id, title, platform_id, package_name, is_disc_primary, is_favorite,
                                   favorite_sort_order, total_play_time_millis, is_manual_entry,
                                   created_at, content_type, is_missing)
                VALUES (1, 'Hellraiser', 'windows', 'com.winlator.banner', 1, 0, 0, 0, 1, 0, 'GAME', 0)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(57, listOf(PFPDatabase.MIGRATION_56_57)).use { db ->
            assertEquals(1, db.count("SELECT COUNT(*) FROM games WHERE id = 1 AND windows_label IS NULL"))
        }
    }

    companion object {
        const val DB = "migration-57-test"
    }
}
