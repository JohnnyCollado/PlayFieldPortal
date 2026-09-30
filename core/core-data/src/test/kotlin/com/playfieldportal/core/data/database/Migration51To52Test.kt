package com.playfieldportal.core.data.database

import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * v52 — `artwork_records.crop_at_draw`: an animated file kept uncropped in a cropped slot, framed
 * while drawing instead. Every existing row was baked (or never cropped), so the migration must
 * mark nothing: a row it flipped would be cropped a second time on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration51To52Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    @Test
    fun `existing records keep their baked crop and are not cropped again at draw time`() {
        helper.createDatabase(51).use { db ->
            db.execSQL(record(id = 1, cropRect = "0.1000,0.2000,0.9000,0.8000"))
            db.execSQL(record(id = 2, cropRect = null))
        }

        helper.runMigrationsAndValidate(52, listOf(PFPDatabase.MIGRATION_51_52)).use { db ->
            assertEquals(0, db.count("SELECT COUNT(*) FROM artwork_records WHERE crop_at_draw != 0"))
            assertEquals(
                "0.1000,0.2000,0.9000,0.8000",
                db.singleRow("SELECT crop_rect FROM artwork_records WHERE id = 1") { it.getText(0) },
            )
        }
    }

    @Test
    fun `a crop-at-draw record can be written after the migration`() {
        helper.createDatabase(51).use { }

        helper.runMigrationsAndValidate(52, listOf(PFPDatabase.MIGRATION_51_52)).use { db ->
            db.execSQL(record(id = 3, cropRect = "0.0000,0.2500,1.0000,0.7500", cropAtDraw = true))

            assertEquals(1, db.count("SELECT COUNT(*) FROM artwork_records WHERE crop_at_draw = 1"))
        }
    }

    /**
     * A minimal `artwork_records` row: every NOT NULL column, plus the crop being tested. Each row
     * is its own game's ICON, since (game, type, position) is unique — one record per slot.
     */
    private fun record(id: Long, cropRect: String?, cropAtDraw: Boolean? = null): String {
        val extraColumn = if (cropAtDraw != null) ", crop_at_draw" else ""
        val extraValue = if (cropAtDraw != null) ", ${if (cropAtDraw) 1 else 0}" else ""
        val rect = cropRect?.let { "'$it'" } ?: "NULL"
        return """
            INSERT INTO artwork_records
                (id, game_id, platform_id, artwork_type, sort_order, portable_name, relative_path,
                 document_uri, source, size_bytes, user_assigned, locked, prev_size_bytes, crop_rect,
                 has_original, created_at, updated_at$extraColumn)
            VALUES ($id, $id, 'psx', 'ICON', 0, 'Game $id', 'psx/icons/Game $id.webp',
                    'content://tree/psx/icons/Game%20$id.webp', 'sgdb', 1024, 1, 1, 0, $rect,
                    0, 1700000000000, 1700000000000$extraValue)
        """.trimIndent()
    }

    companion object {
        const val DB = "migration-52-test"
    }
}
