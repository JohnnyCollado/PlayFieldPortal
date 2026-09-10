package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.playfieldportal.core.data.database.entity.ArtworkRecordEntity

@Dao
interface ArtworkRecordDao {

    // REPLACE rides the unique (game_id, artwork_type, sort_order) index — one record per game
    // per type per position. Single-art kinds only ever occupy position 0.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(rows: List<ArtworkRecordEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ArtworkRecordEntity)

    @Query("SELECT * FROM artwork_records WHERE game_id = :gameId ORDER BY artwork_type, sort_order")
    suspend fun getForGame(gameId: Long): List<ArtworkRecordEntity>

    /** The primary record for this slot (position 0, or the lowest position present). */
    @Query("""
        SELECT * FROM artwork_records
        WHERE game_id = :gameId AND artwork_type = :type
        ORDER BY sort_order ASC LIMIT 1
    """)
    suspend fun get(gameId: Long, type: String): ArtworkRecordEntity?

    /** The record at an exact position, or null. */
    @Query("SELECT * FROM artwork_records WHERE game_id = :gameId AND artwork_type = :type AND sort_order = :sortOrder")
    suspend fun getAt(gameId: Long, type: String, sortOrder: Int): ArtworkRecordEntity?

    /** Every record of one kind for a game, in display order. */
    @Query("SELECT * FROM artwork_records WHERE game_id = :gameId AND artwork_type = :type ORDER BY sort_order ASC")
    suspend fun findAll(gameId: Long, type: String): List<ArtworkRecordEntity>

    /** Highest position in use for this slot, or -1 when the slot is empty — append writes here + 1. */
    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM artwork_records WHERE game_id = :gameId AND artwork_type = :type")
    suspend fun maxSortOrder(gameId: Long, type: String): Int

    @Query("SELECT COUNT(*) FROM artwork_records WHERE game_id = :gameId AND artwork_type = :type")
    suspend fun countFor(gameId: Long, type: String): Int

    // Write-time collision check: another game already using this portable name for this
    // platform/type (case-insensitive — FAT volumes are). Portable names carry the ordinal, so
    // two positions of one game are distinct names and never collide with each other.
    @Query("""
        SELECT * FROM artwork_records
        WHERE platform_id = :platformId AND artwork_type = :type
          AND portable_name = :portableName COLLATE NOCASE AND game_id != :gameId
    """)
    suspend fun findNameCollisions(platformId: String, type: String, portableName: String, gameId: Long): List<ArtworkRecordEntity>

    // ── Duplicate detection (C16 task 5.3 groundwork) ─────────────────────────
    // Same asset already applied to this game, by the three identities an asset can carry:
    // the provider's own id for it, the URL it came from, and the bytes themselves.

    @Query("""
        SELECT * FROM artwork_records
        WHERE game_id = :gameId AND artwork_type = :type AND provider_asset_id = :providerAssetId
    """)
    suspend fun findByProviderAssetId(gameId: Long, type: String, providerAssetId: String): List<ArtworkRecordEntity>

    @Query("SELECT * FROM artwork_records WHERE game_id = :gameId AND artwork_type = :type AND origin_url = :originUrl")
    suspend fun findByOriginUrl(gameId: Long, type: String, originUrl: String): List<ArtworkRecordEntity>

    @Query("SELECT * FROM artwork_records WHERE game_id = :gameId AND artwork_type = :type AND checksum = :checksum")
    suspend fun findByChecksum(gameId: Long, type: String, checksum: String): List<ArtworkRecordEntity>

    @Query("SELECT * FROM artwork_records")
    suspend fun getAll(): List<ArtworkRecordEntity>

    @Query("DELETE FROM artwork_records WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM artwork_records WHERE game_id = :gameId AND artwork_type = :type AND sort_order = :sortOrder")
    suspend fun deleteAt(gameId: Long, type: String, sortOrder: Int)

    @Query("UPDATE artwork_records SET sort_order = :sortOrder, updated_at = :updatedAt WHERE id = :id")
    suspend fun setSortOrder(id: Long, sortOrder: Int, updatedAt: Long)

    /**
     * Rewrites the positions of one slot to exactly [orderedIds], atomically.
     *
     * Two passes: every affected row is first parked at a negative position, then written to its
     * final one. A single pass would transiently collide with the unique
     * (game_id, artwork_type, sort_order) index whenever two rows swap places.
     *
     * Ids that are not part of this slot are ignored; rows of the slot that [orderedIds] omits
     * keep their relative order and follow the listed ones.
     */
    @Transaction
    suspend fun reorder(gameId: Long, type: String, orderedIds: List<Long>) {
        val current = findAll(gameId, type)
        if (current.isEmpty()) return
        val byId = current.associateBy { it.id }
        val listed = orderedIds.distinct().mapNotNull { byId[it] }
        val rest = current.filter { row -> listed.none { it.id == row.id } }
        val target = listed + rest
        if (target.map { it.id } == current.map { it.id }) return

        val now = System.currentTimeMillis()
        // Park out of the way — negative positions cannot collide with any final position.
        target.forEachIndexed { index, row -> setSortOrder(row.id, -(index + 1), now) }
        target.forEachIndexed { index, row -> setSortOrder(row.id, index, now) }
    }

    /**
     * Closes the gap left by removing a position, so a slot's orders stay 0..n-1 with the
     * primary always at 0. Runs in the same transaction as the delete.
     */
    @Transaction
    suspend fun deleteAtAndCompact(gameId: Long, type: String, sortOrder: Int) {
        deleteAt(gameId, type, sortOrder)
        val now = System.currentTimeMillis()
        val remaining = findAll(gameId, type)
        remaining.forEachIndexed { index, row -> setSortOrder(row.id, -(index + 1), now) }
        remaining.forEachIndexed { index, row -> setSortOrder(row.id, index, now) }
    }

    @Query("SELECT COUNT(*) FROM artwork_records")
    suspend fun count(): Int

    @Query("SELECT COALESCE(SUM(size_bytes), 0) FROM artwork_records")
    suspend fun totalBytes(): Long

    @Query("DELETE FROM artwork_records WHERE game_id = :gameId")
    suspend fun deleteForGame(gameId: Long)

    @Query("DELETE FROM artwork_records")
    suspend fun clear()
}
