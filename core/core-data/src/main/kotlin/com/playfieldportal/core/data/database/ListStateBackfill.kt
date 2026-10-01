package com.playfieldportal.core.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Brings data written before v54 onto the per-list model. Run by the 53→54 migration, and again
 * after restoring a backup that predates it — the archive carries the same old shapes.
 *
 * Every statement is safe to repeat: a second run finds nothing left to change.
 */
object ListStateBackfill {

    val STATEMENTS: List<String> = listOf(
        // A collection whose category was deleted pointed at nothing and vanished from the XMB.
        """
        UPDATE collections SET category_id = 'games'
        WHERE category_id NOT IN (SELECT id FROM categories)
        """.trimIndent(),
        // Collection order becomes 0..n-1 within its category (it was one global sequence).
        """
        UPDATE collections SET sort_order = (
            SELECT COUNT(*) FROM collections other
            WHERE other.category_id = collections.category_id
              AND (other.sort_order < collections.sort_order
                   OR (other.sort_order = collections.sort_order AND other.id < collections.id))
        )
        """.trimIndent(),
        // A custom category's games now live on its Memory Card list; their pins move with them.
        """
        INSERT OR IGNORE INTO list_items (list_key, item_key, position, pinned)
        SELECT 'catcard:' || category_id, 'game:' || item_id, NULL, 1
        FROM category_items
        WHERE pinned = 1 AND item_type = 'game'
        """.trimIndent(),
    )

    fun run(db: SupportSQLiteDatabase) {
        STATEMENTS.forEach(db::execSQL)
    }
}

/** [ListStateBackfill] for callers outside a migration (backup restore). */
@Singleton
class ListStateBackfiller @Inject constructor(
    private val database: PFPDatabase,
) {
    fun run() = ListStateBackfill.run(database.openHelper.writableDatabase)
}
