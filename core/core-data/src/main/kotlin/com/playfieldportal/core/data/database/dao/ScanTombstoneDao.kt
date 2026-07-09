package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import com.playfieldportal.core.data.database.entity.ScanTombstoneEntity
import androidx.room.Query

@Dao
interface ScanTombstoneDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tombstone: ScanTombstoneEntity)

    // The scanner's skip-set for one platform's folder walk.
    @Query("SELECT rom_path FROM scan_tombstones WHERE platform_id = :platformId")
    suspend fun getPathsForPlatform(platformId: String): List<String>

    @Query("DELETE FROM scan_tombstones WHERE rom_path = :romPath")
    suspend fun deleteForPath(romPath: String)

    // Removing a Memory Card already deletes its games; its tombstones go with it so a
    // re-added card starts from a clean scan.
    @Query("DELETE FROM scan_tombstones WHERE platform_id = :platformId")
    suspend fun clearPlatform(platformId: String)
}
