package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.playfieldportal.core.data.database.entity.ArtworkOrphanFileEntity
import kotlinx.coroutines.flow.Flow

/**
 * The files Scan & Relink could not place (C22 task T1).
 *
 * Replacement is always **clear-then-write over a scope**, never a merge: a relink's output is the
 * complete truth for the platforms it walked, so a file that has since been matched or deleted
 * must disappear rather than linger. The two replace methods are `@Transaction` so a walk can
 * never leave the table half-cleared.
 */
@Dao
interface ArtworkOrphanFileDao {

    @Query("SELECT * FROM artwork_orphan_files ORDER BY platform_id, artwork_type, file_name")
    fun observeAll(): Flow<List<ArtworkOrphanFileEntity>>

    @Query("SELECT * FROM artwork_orphan_files ORDER BY platform_id, artwork_type, file_name")
    suspend fun getAll(): List<ArtworkOrphanFileEntity>

    @Query("SELECT COUNT(*) FROM artwork_orphan_files")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<ArtworkOrphanFileEntity>)

    @Query("DELETE FROM artwork_orphan_files")
    suspend fun deleteAll()

    @Query("DELETE FROM artwork_orphan_files WHERE platform_id IN (:platformIds)")
    suspend fun deleteForPlatforms(platformIds: Collection<String>)

    @Query(
        "DELETE FROM artwork_orphan_files " +
            "WHERE platform_id = :platformId AND artwork_type = :artworkType AND file_name = :fileName"
    )
    suspend fun delete(platformId: String, artworkType: String, fileName: String)

    /** A full-library walk's result: this table now says exactly what that walk found. */
    @Transaction
    suspend fun replaceAll(rows: List<ArtworkOrphanFileEntity>) {
        deleteAll()
        insertAll(rows)
    }

    /**
     * A scoped walk's result: only [platformIds] are rewritten. Every other platform's rows are
     * left exactly as the last walk that covered them left them — a scoped relink knows nothing
     * about platforms it did not visit and must not speak for them.
     */
    @Transaction
    suspend fun replaceForPlatforms(platformIds: Collection<String>, rows: List<ArtworkOrphanFileEntity>) {
        if (platformIds.isEmpty()) return
        deleteForPlatforms(platformIds)
        insertAll(rows.filter { it.platformId in platformIds })
    }
}
