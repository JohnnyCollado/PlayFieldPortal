package com.playfieldportal.core.data.database

import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * v42 — artwork multi-media. The migration must be purely additive: every existing asset keeps
 * its bytes, its provenance and its slot, arriving at `sort_order = 0`, while the rebuilt unique
 * index starts allowing a second and third asset of the multi-asset kinds.
 *
 * Validated at 43 rather than 42: Room only ever exports the schema of the version the database
 * currently declares, and 41→42→43 landed together, so no 42.json exists to validate against.
 * Both migrations run here; the assertions are all about what 41→42 does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration41To42Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    @Test
    fun `every existing asset survives at sort order zero with its provenance intact`() {
        helper.createDatabase(41).use { db ->
            // One asset of several types, including a user-pinned one.
            insertV41Record(db, gameId = 1, type = "ICON", name = "Crash", userAssigned = 1, locked = 1)
            insertV41Record(db, gameId = 1, type = "SCREENSHOT", name = "Crash")
            insertV41Record(db, gameId = 1, type = "VIDEO", name = "Crash")
            insertV41Record(db, gameId = 2, type = "BOX_ART", name = "Spyro")
        }

        helper.runMigrationsAndValidate(43, MIGRATIONS).use { db ->
            assertEquals(4, db.count("SELECT COUNT(*) FROM artwork_records"))
            assertEquals(4, db.count("SELECT COUNT(*) FROM artwork_records WHERE sort_order = 0"))

            db.singleRow(
                "SELECT user_assigned, locked, portable_name, provider_asset_id, crop_profile_key " +
                    "FROM artwork_records WHERE game_id = 1 AND artwork_type = 'ICON'"
            ) {
                assertEquals(1L, it.getLong(0))
                assertEquals(1L, it.getLong(1))
                assertEquals("Crash", it.getText(2))
                assertTrue(it.isNull(3), "provider_asset_id should start empty")
                assertTrue(it.isNull(4), "crop_profile_key should start empty")
            }
        }
    }

    @Test
    fun `multiple screenshots and videos insert after the migration`() {
        helper.createDatabase(41).use { db ->
            insertV41Record(db, gameId = 1, type = "SCREENSHOT", name = "Crash")
            insertV41Record(db, gameId = 1, type = "VIDEO", name = "Crash")
        }

        helper.runMigrationsAndValidate(43, MIGRATIONS).use { db ->
            insertRecord(db, gameId = 1, type = "SCREENSHOT", name = "Crash_01", sortOrder = 1)
            insertRecord(db, gameId = 1, type = "SCREENSHOT", name = "Crash_02", sortOrder = 2)
            insertRecord(db, gameId = 1, type = "VIDEO", name = "Crash_01", sortOrder = 1)

            val orders = db.rows(
                "SELECT sort_order FROM artwork_records " +
                    "WHERE game_id = 1 AND artwork_type = 'SCREENSHOT' ORDER BY sort_order"
            ) { it.getLong(0).toInt() }
            assertEquals(listOf(0, 1, 2), orders)
            assertEquals(2, db.count("SELECT COUNT(*) FROM artwork_records WHERE artwork_type = 'VIDEO'"))
        }
    }

    @Test
    fun `single-art replacement still yields exactly one active record`() {
        helper.createDatabase(41).use { db ->
            insertV41Record(db, gameId = 1, type = "ICON", name = "Crash")
        }

        helper.runMigrationsAndValidate(43, MIGRATIONS).use { db ->
            // The unique index still rides position 0, so a REPLACE-style write collides exactly
            // as it did before and a second icon at the same position is rejected.
            assertFailsWith<Throwable> {
                insertRecord(db, gameId = 1, type = "ICON", name = "Crash", sortOrder = 0)
            }
            assertEquals(1, db.count("SELECT COUNT(*) FROM artwork_records WHERE artwork_type = 'ICON'"))
        }
    }

    @Test
    fun `one game's positions never collide with another game's`() {
        helper.createDatabase(41).use { _ -> }

        helper.runMigrationsAndValidate(43, MIGRATIONS).use { db ->
            insertRecord(db, gameId = 1, type = "SCREENSHOT", name = "A_01", sortOrder = 1)
            insertRecord(db, gameId = 2, type = "SCREENSHOT", name = "B_01", sortOrder = 1)
            assertEquals(2, db.count("SELECT COUNT(*) FROM artwork_records WHERE sort_order = 1"))
        }
    }

    private companion object {
        const val DB = "migration-42-test"

        val MIGRATIONS = listOf(PFPDatabase.MIGRATION_41_42, PFPDatabase.MIGRATION_42_43)

        fun insertV41Record(
            db: androidx.sqlite.SQLiteConnection,
            gameId: Long,
            type: String,
            name: String,
            userAssigned: Int = 0,
            locked: Int = 0,
        ) = db.execSQL(
            "INSERT INTO artwork_records (game_id, platform_id, artwork_type, portable_name, " +
                "relative_path, document_uri, source, size_bytes, user_assigned, locked, " +
                "prev_size_bytes, has_original, created_at, updated_at) " +
                "VALUES ($gameId, 'psx', '$type', '$name', 'Artwork/psx/covers/$name.png', " +
                "'content://tree/$name-$type.png', 'scrape', 1024, $userAssigned, $locked, 0, 0, 1, 1)"
        )

        fun insertRecord(
            db: androidx.sqlite.SQLiteConnection,
            gameId: Long,
            type: String,
            name: String,
            sortOrder: Int,
        ) = db.execSQL(
            "INSERT INTO artwork_records (game_id, platform_id, artwork_type, sort_order, " +
                "portable_name, relative_path, document_uri, source, size_bytes, user_assigned, " +
                "locked, prev_size_bytes, has_original, created_at, updated_at) " +
                "VALUES ($gameId, 'psx', '$type', $sortOrder, '$name', " +
                "'Artwork/psx/screenshots/$name.png', 'content://tree/$gameId-$name-$type.png', " +
                "'user', 2048, 0, 0, 0, 0, 1, 1)"
        )
    }
}
