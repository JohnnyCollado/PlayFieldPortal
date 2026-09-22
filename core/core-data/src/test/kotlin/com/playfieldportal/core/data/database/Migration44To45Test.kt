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
 * v45 — the `notifications` table.
 *
 * One CREATE TABLE and four indices; nothing existing is touched, which is what makes this one of
 * the few trivially non-destructive migrations in the file. The two things worth pinning are that
 * a pre-existing library survives it untouched, and that `source_key` is genuinely UNIQUE — the
 * dedupe rule the repository relies on is enforced by that index, not by application code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration44To45Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    @Test
    fun `an existing library survives and gains an empty notification history`() {
        helper.createDatabase(44).use { db ->
            db.execSQL(
                "INSERT INTO memory_cards " +
                    "(platform_id, display_name, enabled, pinned, sort_order, supported_extensions, " +
                    "scan_recursively, game_count) " +
                    "VALUES ('psx', 'PlayStation', 1, 0, 3, 'cue,bin', 1, 214)"
            )
        }

        helper.runMigrationsAndValidate(45, listOf(PFPDatabase.MIGRATION_44_45)).use { db ->
            db.singleRow("SELECT display_name, game_count FROM memory_cards WHERE platform_id = 'psx'") {
                assertEquals("PlayStation", it.getText(0))
                assertEquals(214, it.getLong(1).toInt())
            }
            assertEquals(0, db.count("SELECT COUNT(*) FROM notifications"))
        }
    }

    @Test
    fun `a row round-trips through the new table`() {
        helper.createDatabase(44).use { }

        helper.runMigrationsAndValidate(45, listOf(PFPDatabase.MIGRATION_44_45)).use { db ->
            db.execSQL(
                "INSERT INTO notifications " +
                    "(kind, severity, title, body, source_key, action_type, action_arg, payload, " +
                    "created_at, read_at) " +
                    "VALUES ('SCAN', 'ERROR', 'Scan failed', 'no such folder', 'scan:psx', " +
                    "'open_memory_card', 'psx', NULL, 500, NULL)"
            )
            db.singleRow(
                "SELECT kind, severity, title, source_key, action_arg, created_at, read_at " +
                    "FROM notifications"
            ) {
                assertEquals("SCAN", it.getText(0))
                assertEquals("ERROR", it.getText(1))
                assertEquals("Scan failed", it.getText(2))
                assertEquals("scan:psx", it.getText(3))
                assertEquals("psx", it.getText(4))
                assertEquals(500, it.getLong(5).toInt())
                assertTrue(it.isNull(6), "a fresh row starts unread")
            }
        }
    }

    @Test
    fun `source_key is unique, and null keys are still free to repeat`() {
        helper.createDatabase(44).use { }

        helper.runMigrationsAndValidate(45, listOf(PFPDatabase.MIGRATION_44_45)).use { db ->
            db.execSQL(
                "INSERT INTO notifications (kind, severity, title, source_key, created_at) " +
                    "VALUES ('SCAN', 'INFO', 'first', 'scan:psx', 1)"
            )
            val clash = runCatching {
                db.execSQL(
                    "INSERT INTO notifications (kind, severity, title, source_key, created_at) " +
                        "VALUES ('SCAN', 'INFO', 'second', 'scan:psx', 2)"
                )
            }
            assertTrue(clash.isFailure, "a second row with the same source_key must be rejected")

            // NULLs are distinct in a SQLite unique index, so keyless rows still append freely.
            db.execSQL(
                "INSERT INTO notifications (kind, severity, title, source_key, created_at) " +
                    "VALUES ('SYSTEM', 'INFO', 'keyless one', NULL, 3)"
            )
            db.execSQL(
                "INSERT INTO notifications (kind, severity, title, source_key, created_at) " +
                    "VALUES ('SYSTEM', 'INFO', 'keyless two', NULL, 4)"
            )
            assertEquals(3, db.count("SELECT COUNT(*) FROM notifications"))
        }
    }

    private companion object {
        const val DB = "migration-45-test"
    }
}
