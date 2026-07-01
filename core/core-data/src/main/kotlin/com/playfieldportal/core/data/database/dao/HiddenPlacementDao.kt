package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.HiddenPlacementEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HiddenPlacementDao {

    @Query("SELECT * FROM hidden_placements ORDER BY item_label COLLATE NOCASE ASC, location_label COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<HiddenPlacementEntity>>

    @Query("SELECT * FROM hidden_placements")
    suspend fun getAll(): List<HiddenPlacementEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(placement: HiddenPlacementEntity)

    @Query("DELETE FROM hidden_placements WHERE item_key = :itemKey AND location_type = :locationType AND location_id = :locationId")
    suspend fun delete(itemKey: String, locationType: String, locationId: String)

    @Query("DELETE FROM hidden_placements WHERE item_key = :itemKey")
    suspend fun deleteAllForItem(itemKey: String)

    // Housekeeping: drop placements pointing at a category/collection that no longer exists.
    @Query("DELETE FROM hidden_placements WHERE location_type = :locationType AND location_id = :locationId")
    suspend fun deleteForLocation(locationType: String, locationId: String)
}
