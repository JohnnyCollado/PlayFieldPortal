package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.AppOverrideEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AppOverrideDao {

    @Query("SELECT * FROM app_overrides")
    fun observeAll(): Flow<List<AppOverrideEntity>>

    @Query("SELECT * FROM app_overrides")
    suspend fun getAll(): List<AppOverrideEntity>

    @Query("SELECT * FROM app_overrides WHERE package_name = :pkg")
    suspend fun getByPackage(pkg: String): AppOverrideEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(override: AppOverrideEntity)

    @Query("UPDATE app_overrides SET is_hidden = :hidden WHERE package_name = :pkg")
    suspend fun setHidden(pkg: String, hidden: Boolean)

    @Query("SELECT COUNT(*) FROM app_overrides WHERE is_hidden = 1")
    suspend fun countHidden(): Int

    // Clears the hidden flag on every app — the user's recovery path for apps hidden from categories.
    @Query("UPDATE app_overrides SET is_hidden = 0 WHERE is_hidden = 1")
    suspend fun unhideAll()

    @Query("UPDATE app_overrides SET custom_label = :label WHERE package_name = :pkg")
    suspend fun setCustomLabel(pkg: String, label: String?)

    @Query("DELETE FROM app_overrides WHERE package_name = :pkg")
    suspend fun delete(pkg: String)
}
