package com.playfieldportal.core.data.database

import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration38To39Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    @Test
    fun `v39 repairs duplicate primaries with m3u and disc ordering`() {
        helper.createDatabase(38).use { db ->
            val suffix = System.nanoTime()
            val columns = "title, platform_id, rom_path, disc_set_key, disc_number, is_disc_primary, " +
                "is_favorite, favorite_sort_order, total_play_time_millis, content_type, is_missing, is_manual_entry, created_at"
            fun insert(title: String, path: String, key: String, number: String, created: Int) {
                db.execSQL("INSERT INTO games ($columns) VALUES ('$title', 'psx', '$path', '$key', $number, 1, 0, 0, 0, 'GAME', 0, 0, $created)")
            }
            insert("Disc 2", "/roms/disc2-$suffix.cue", "set-a", "2", 1)
            insert("Playlist", "/roms/game-$suffix.m3u", "set-a", "NULL", 2)
            insert("Disc 1", "/roms/disc1-$suffix.cue", "set-a", "1", 3)
            insert("Other", "/roms/other-$suffix.cue", "set-b", "1", 4)
            insert("Other 2", "/roms/other2-$suffix.cue", "set-b", "2", 5)
        }

        helper.runMigrationsAndValidate(39, listOf(PFPDatabase.MIGRATION_38_39)).use { db ->
            // The m3u playlist wins the primary slot for set-a, and it is the only primary there.
            val setA = db.rows("SELECT disc_number FROM games WHERE disc_set_key = 'set-a' AND is_disc_primary = 1") {
                if (it.isNull(0)) null else it.getLong(0).toInt()
            }
            assertEquals(1, setA.size)
            assertNull(setA.single())

            // set-b has no playlist, so the lowest disc number keeps the primary slot.
            val setB = db.rows("SELECT disc_number FROM games WHERE disc_set_key = 'set-b' AND is_disc_primary = 1") {
                it.getLong(0).toInt()
            }
            assertEquals(listOf(1), setB)

            // Multiple non-primary rows may share a set key (no DB-level uniqueness on it — the
            // one-primary invariant is enforced at scan time by DiscSetBuilder/DiscSetReconciler).
            db.execSQL("INSERT INTO games (title, platform_id, rom_path, disc_set_key, disc_number, is_disc_primary, is_favorite, favorite_sort_order, total_play_time_millis, content_type, is_missing, is_manual_entry, created_at) VALUES ('Disc 3', 'psx', '/roms/disc3.cue', 'set-a', 3, 0, 0, 0, 0, 'GAME', 0, 0, 6)")
        }
    }

    private companion object {
        const val DB = "migration-39-test-v7"
    }
}
