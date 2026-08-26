package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import com.playfieldportal.core.data.database.entity.ScanTombstoneEntity
import androidx.room.Query

/**
 * RETIRED. Removal no longer writes scan tombstones and scans no longer read them (see
 * [ScanTombstoneEntity]). The DAO and its queries remain only because Room still registers the
 * `scan_tombstones` table for existing installs; no new code should call these.
 */
@Dao
interface ScanTombstoneDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tombstone: ScanTombstoneEntity)

    @Query("SELECT rom_path FROM scan_tombstones WHERE platform_id = :platformId")
    suspend fun getPathsForPlatform(platformId: String): List<String>

    @Query("DELETE FROM scan_tombstones WHERE rom_path = :romPath")
    suspend fun deleteForPath(romPath: String)

    @Query("DELETE FROM scan_tombstones WHERE platform_id = :platformId")
    suspend fun clearPlatform(platformId: String)
}
