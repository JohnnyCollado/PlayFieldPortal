package com.playfieldportal.core.data.database

import androidx.sqlite.execSQL
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * v54 — per-list state: `list_items` (custom order + pins), `list_settings` (sort override),
 * `umd_slots` (the inserted game per gaming column), `app_usage` (launch recency) and
 * `category_items.added_at`. The backfill re-homes collections whose category is gone, makes
 * collection order per-category, and carries a custom category's pinned games onto its
 * Memory Card list — none of which may touch a row that was already sound.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class Migration53To54Test {

    @get:Rule
    val helper = migrationTestHelper(DB)

    private val migrations = listOf(PFPDatabase.MIGRATION_53_54)

    @Test
    fun `empty database migrates and validates against schema 54`() {
        helper.createDatabase(53).close()

        helper.runMigrationsAndValidate(54, migrations).use { db ->
            assertEquals(0, db.count("SELECT COUNT(*) FROM list_items"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM list_settings"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM umd_slots"))
            assertEquals(0, db.count("SELECT COUNT(*) FROM app_usage"))
        }
    }

    @Test
    fun `orphaned collection is rehomed to games`() {
        helper.createDatabase(53).use { db ->
            db.execSQL(category("games"))
            db.execSQL(category("custom_rpg_9"))
            db.execSQL(collection(id = 1, name = "Kept", categoryId = "custom_rpg_9", sortOrder = 0))
            db.execSQL(collection(id = 2, name = "Orphan", categoryId = "custom_deleted_3", sortOrder = 1))
        }

        helper.runMigrationsAndValidate(54, migrations).use { db ->
            assertEquals(
                listOf("custom_rpg_9", "games"),
                db.rows("SELECT category_id FROM collections ORDER BY id") { it.getText(0) },
            )
        }
    }

    @Test
    fun `collection sort_order is renumbered per category, ties by id`() {
        helper.createDatabase(53).use { db ->
            db.execSQL(category("games"))
            db.execSQL(category("custom_rpg_9"))
            // Global order today: 0, 1, 2, 2, 5 across two categories.
            db.execSQL(collection(id = 1, name = "A", categoryId = "games", sortOrder = 0))
            db.execSQL(collection(id = 2, name = "B", categoryId = "custom_rpg_9", sortOrder = 1))
            db.execSQL(collection(id = 3, name = "C", categoryId = "games", sortOrder = 2))
            db.execSQL(collection(id = 4, name = "D", categoryId = "games", sortOrder = 2))
            db.execSQL(collection(id = 5, name = "E", categoryId = "custom_rpg_9", sortOrder = 5))
        }

        helper.runMigrationsAndValidate(54, migrations).use { db ->
            assertEquals(
                listOf("A=0", "B=0", "C=1", "D=2", "E=1"),
                db.rows("SELECT name, sort_order FROM collections ORDER BY id") {
                    "${it.getText(0)}=${it.getLong(1)}"
                },
            )
        }
    }

    @Test
    fun `collection renumbering follows sort_order, not id order`() {
        helper.createDatabase(53).use { db ->
            db.execSQL(category("games"))
            // A user who reordered cards: sort_order no longer follows id.
            db.execSQL(collection(id = 1, name = "A", categoryId = "games", sortOrder = 10))
            db.execSQL(collection(id = 2, name = "B", categoryId = "games", sortOrder = 5))
            db.execSQL(collection(id = 3, name = "C", categoryId = "games", sortOrder = 7))
            db.execSQL(collection(id = 5, name = "D", categoryId = "games", sortOrder = 1))
            db.execSQL(collection(id = 7, name = "E", categoryId = "games", sortOrder = 8))
        }

        helper.runMigrationsAndValidate(54, migrations).use { db ->
            assertEquals(
                listOf("D=0", "B=1", "C=2", "E=3", "A=4"),
                db.rows("SELECT name, sort_order FROM collections ORDER BY sort_order") {
                    "${it.getText(0)}=${it.getLong(1)}"
                },
            )
        }
    }

    @Test
    fun `pinned games of a custom category are pinned on its memory card list`() {
        helper.createDatabase(53).use { db ->
            db.execSQL(category("custom_rpg_9"))
            db.execSQL(categoryItem("custom_rpg_9", itemId = "7", type = "game", pinned = true))
            db.execSQL(categoryItem("custom_rpg_9", itemId = "8", type = "game", pinned = false))
            db.execSQL(categoryItem("custom_rpg_9", itemId = "com.app", type = "app", pinned = true))
        }

        helper.runMigrationsAndValidate(54, migrations).use { db ->
            assertEquals(
                listOf("catcard:custom_rpg_9|game:7|1"),
                db.rows("SELECT list_key, item_key, pinned FROM list_items") {
                    "${it.getText(0)}|${it.getText(1)}|${it.getLong(2)}"
                },
            )
            // The app's own pin stays where app lists read it.
            assertEquals(
                1,
                db.count("SELECT pinned FROM category_items WHERE item_id = 'com.app'"),
            )
        }
    }

    @Test
    fun `category_items gains added_at defaulting to 0`() {
        helper.createDatabase(53).use { db ->
            db.execSQL(category("custom_rpg_9"))
            db.execSQL(categoryItem("custom_rpg_9", itemId = "7", type = "game", pinned = false))
        }

        helper.runMigrationsAndValidate(54, migrations).use { db ->
            assertEquals(0, db.count("SELECT added_at FROM category_items WHERE item_id = '7'"))
        }
    }

    @Test
    fun `a umd slot is removed with its category`() {
        helper.createDatabase(53).use { db ->
            db.execSQL(category("custom_rpg_9"))
        }

        helper.runMigrationsAndValidate(54, migrations).use { db ->
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL("INSERT INTO umd_slots (column_id, game_id, inserted_at) VALUES ('custom_rpg_9', 7, 1)")
            db.execSQL("DELETE FROM categories WHERE id = 'custom_rpg_9'")

            assertEquals(0, db.count("SELECT COUNT(*) FROM umd_slots"))
        }
    }

    private fun category(id: String): String = """
        INSERT INTO categories (id, name, icon_key, type, position, is_visible, is_gaming_category)
        VALUES ('$id', '$id', 'ic_games', 'MANUAL', 0, 1, 1)
    """.trimIndent()

    private fun collection(id: Int, name: String, categoryId: String, sortOrder: Int): String = """
        INSERT INTO collections (id, name, category_id, is_pinned, created_at, updated_at, sort_order)
        VALUES ($id, '$name', '$categoryId', 0, 1700000000000, 1700000000000, $sortOrder)
    """.trimIndent()

    private fun categoryItem(categoryId: String, itemId: String, type: String, pinned: Boolean): String = """
        INSERT INTO category_items (category_id, item_id, item_type, sort_order, pinned)
        VALUES ('$categoryId', '$itemId', '$type', 0, ${if (pinned) 1 else 0})
    """.trimIndent()

    companion object {
        const val DB = "migration-54-test"
    }
}
