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
 * v44 — `scan_signature` on the three media library tables.
 *
 * The column is what lets opening a media card cost one cursor query per directory instead of a
 * full probe. Two things have to hold for that to be safe: existing rows must survive the
 * migration untouched, and they must come back with a NULL signature — "never signed" is what
 * forces one real scan before the fast path can ever engage. A non-null default here would make
 * every pre-existing library instantly claim to be up to date.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration43To44Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    @Test
    fun `existing media libraries survive and read back unsigned`() {
        helper.createDatabase(43).use { db ->
            db.execSQL(
                "INSERT INTO music_folders " +
                    "(id, display_name, tree_uri, enabled, track_count, last_scanned_at, created_at, updated_at) " +
                    "VALUES ('m1', 'Albums', '$MUSIC_URI', 1, 12, 500, 1, 2)"
            )
            db.execSQL(
                "INSERT INTO photo_libraries " +
                    "(id, display_name, tree_uri, enabled, scan_recursively, photo_count, last_scanned_at, created_at, updated_at) " +
                    "VALUES ('p1', 'Camera', '$PHOTO_URI', 1, 1, 340, 600, 1, 2)"
            )
            db.execSQL(
                "INSERT INTO video_libraries " +
                    "(id, display_name, tree_uri, enabled, scan_recursively, video_count, last_scanned_at, created_at, updated_at) " +
                    "VALUES ('v1', 'Movies', '$VIDEO_URI', 1, 1, 7, 700, 1, 2)"
            )
        }

        helper.runMigrationsAndValidate(44, listOf(PFPDatabase.MIGRATION_43_44)).use { db ->
            db.singleRow(
                "SELECT display_name, track_count, last_scanned_at, scan_signature FROM music_folders"
            ) {
                assertEquals("Albums", it.getText(0))
                assertEquals(12, it.getLong(1).toInt())
                assertEquals(500, it.getLong(2).toInt())
                assertTrue(it.isNull(3), "a pre-existing music folder must start unsigned")
            }
            db.singleRow(
                "SELECT display_name, photo_count, last_scanned_at, scan_signature FROM photo_libraries"
            ) {
                assertEquals("Camera", it.getText(0))
                assertEquals(340, it.getLong(1).toInt())
                assertEquals(600, it.getLong(2).toInt())
                assertTrue(it.isNull(3), "a pre-existing photo library must start unsigned")
            }
            db.singleRow(
                "SELECT display_name, video_count, last_scanned_at, scan_signature FROM video_libraries"
            ) {
                assertEquals("Movies", it.getText(0))
                assertEquals(7, it.getLong(1).toInt())
                assertEquals(700, it.getLong(2).toInt())
                assertTrue(it.isNull(3), "a pre-existing video library must start unsigned")
            }
        }
    }

    @Test
    fun `the signature column accepts a written value on each table`() {
        helper.createDatabase(43).use { db ->
            db.execSQL(
                "INSERT INTO video_libraries " +
                    "(id, display_name, tree_uri, enabled, scan_recursively, video_count, last_scanned_at, created_at, updated_at) " +
                    "VALUES ('v1', 'Movies', '$VIDEO_URI', 1, 1, 7, 700, 1, 2)"
            )
        }

        helper.runMigrationsAndValidate(44, listOf(PFPDatabase.MIGRATION_43_44)).use { db ->
            db.execSQL("UPDATE video_libraries SET scan_signature = '7:1024:900' WHERE id = 'v1'")
            db.singleRow("SELECT scan_signature FROM video_libraries WHERE id = 'v1'") {
                assertEquals("7:1024:900", it.getText(0))
            }
        }
    }

    private companion object {
        const val DB = "migration-44-test"
        const val MUSIC_URI = "content://com.android.externalstorage.documents/tree/primary%3AMusic"
        const val PHOTO_URI = "content://com.android.externalstorage.documents/tree/primary%3ADCIM"
        const val VIDEO_URI = "content://com.android.externalstorage.documents/tree/primary%3AMovies"
    }
}
