package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.playfieldportal.core.data.database.entity.AppUsageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppUsageDao {

    @Query("SELECT * FROM app_usage")
    fun observeAll(): Flow<List<AppUsageEntity>>

    @Query("SELECT * FROM app_usage")
    suspend fun getAll(): List<AppUsageEntity>

    @Query("SELECT * FROM app_usage WHERE package_name = :packageName")
    suspend fun get(packageName: String): AppUsageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(usage: AppUsageEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(usages: List<AppUsageEntity>)

    @Query("DELETE FROM app_usage")
    suspend fun clear()

    @Transaction
    suspend fun recordLaunch(packageName: String, at: Long) {
        val count = get(packageName)?.launchCount ?: 0
        upsert(AppUsageEntity(packageName, lastLaunchedAt = at, launchCount = count + 1))
    }
}
