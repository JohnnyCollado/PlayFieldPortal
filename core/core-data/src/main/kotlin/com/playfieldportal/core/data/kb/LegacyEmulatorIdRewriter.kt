package com.playfieldportal.core.data.kb

import androidx.room.withTransaction
import com.playfieldportal.core.data.database.PFPDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Moves stored references to a retired bundled profile id onto the `auto_<pkg>` id that replaces it
 * (AD-6). Game overrides, Memory Card assignments and platform preferences move together in one
 * Room transaction, so a reference is never rewritten in one table and left behind in another.
 *
 * Idempotent: once a legacy id is gone, its UPDATE matches no rows, so a repeat run writes nothing.
 */
@Singleton
class LegacyEmulatorIdRewriter @Inject constructor(
    private val db: PFPDatabase,
) {
    /**
     * @param map retired id to the profile id that replaces it.
     * @param keepIds ids that still name a persisted profile (a user's edited copy); never rewritten.
     * @return rows changed across the three tables.
     */
    suspend fun run(map: Map<String, String>, keepIds: Set<String>): Int {
        val todo = map.filter { (old, new) -> old !in keepIds && old != new }
        if (todo.isEmpty()) return 0
        return db.withTransaction {
            var changed = 0
            for ((old, new) in todo) {
                changed += db.gameDao().renameEmulatorRef(old, new)
                changed += db.memoryCardDao().renameEmulatorId(old, new)
                changed += db.platformDao().renamePreferredEmulator(old, new)
            }
            changed
        }
    }
}
