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
 * v47 — `artwork_orphan_files` (C22 task T1). Purely additive: the table is new and empty, and
 * nothing that already existed may be touched. Artwork records in particular, since the orphan
 * table is derived from the same walk that maintains them and a migration that disturbed one
 * while adding the other would cost a user their artwork links for no reason at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration46To47Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    @Test
    fun `the orphan table is created empty and existing artwork records are untouched`() {
        helper.createDatabase(46).use { db ->
            db.execSQL(
                """
                INSERT INTO artwork_records
                    (game_id, platform_id, artwork_type, sort_order, portable_name, relative_path,
                     document_uri, source, size_bytes, user_assigned, locked, created_at, updated_at,
                     prev_size_bytes, has_original)
                VALUES (7, 'ps2', 'BOX_ART', 0, 'Jak and Daxter (USA)',
                        'ps2/covers/Jak and Daxter (USA).png', 'content://art/1', 'screenscraper',
                        1234, 1, 0, 100, 100, 0, 0)
                """.trimIndent()
            )
        }

        helper.runMigrationsAndValidate(47, listOf(PFPDatabase.MIGRATION_46_47)).use { db ->
            val orphans = db.rows("SELECT COUNT(*) FROM artwork_orphan_files") { it.getLong(0) }
            assertEquals(listOf(0L), orphans)

            val records = db.rows(
                "SELECT portable_name, source, user_assigned FROM artwork_records WHERE game_id = 7"
            ) { Triple(it.getText(0), it.getText(1), it.getLong(2)) }
            assertEquals(
                listOf(Triple("Jak and Daxter (USA)", "screenscraper", 1L)),
                records,
            )
        }
    }

    @Test
    fun `the orphan table is keyed per file and indexed by platform`() {
        helper.createDatabase(46).close()
        helper.runMigrationsAndValidate(47, listOf(PFPDatabase.MIGRATION_46_47)).use { db ->
            // Two kinds of the same file name on one platform are two different orphans; the same
            // (platform, kind, file) twice is one. That is what makes a rewrite idempotent.
            db.execSQL(orphanInsert("ps2", "BOX_ART", "Jak.png"))
            db.execSQL(orphanInsert("ps2", "SCREENSHOT", "Jak.png"))
            db.execSQL(orphanInsert("ps2", "BOX_ART", "Jak.png"))   // REPLACE of the first
            val count = db.rows("SELECT COUNT(*) FROM artwork_orphan_files") { it.getLong(0) }
            assertEquals(listOf(2L), count)

            val indexes = db.rows(
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'artwork_orphan_files'"
            ) { it.getText(0) }
            assertTrue(
                indexes.any { it.contains("platform_id") },
                "expected a platform_id index, got $indexes",
            )
        }
    }

    private fun orphanInsert(platform: String, kind: String, file: String) =
        """
        INSERT OR REPLACE INTO artwork_orphan_files
            (platform_id, artwork_type, file_name, stem, document_uri, size_bytes, seen_at)
        VALUES ('$platform', '$kind', '$file', '${file.substringBeforeLast('.')}',
                'content://orphan/$file', 10, 100)
        """.trimIndent()

    companion object {
        const val DB = "migration-47-test"
    }
}
