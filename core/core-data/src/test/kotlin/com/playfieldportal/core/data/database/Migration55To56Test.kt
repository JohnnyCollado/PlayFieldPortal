package com.playfieldportal.core.data.database

import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * v56 — a Windows game remembers which installed PC launcher the user chose to run it with
 * (Launch With). Every existing game starts with no choice, so it keeps launching exactly as
 * it was imported.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration55To56Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    @Test
    fun `existing games start with no launcher choice`() {
        helper.createDatabase(55).use { db ->
            db.execSQL(
                """
                INSERT INTO games (id, title, platform_id, package_name, is_disc_primary, is_favorite,
                                   favorite_sort_order, total_play_time_millis, is_manual_entry,
                                   created_at, content_type, is_missing)
                VALUES (1, 'Hellraiser', 'windows', 'com.winlator.banner', 1, 0, 0, 0, 1, 0, 'GAME', 0)
                """.trimIndent(),
            )
        }

        helper.runMigrationsAndValidate(56, listOf(PFPDatabase.MIGRATION_55_56)).use { db ->
            assertEquals(1, db.count("SELECT COUNT(*) FROM games WHERE id = 1 AND pc_launcher_package IS NULL"))
        }
    }

    companion object {
        const val DB = "migration-56-test"
    }
}
