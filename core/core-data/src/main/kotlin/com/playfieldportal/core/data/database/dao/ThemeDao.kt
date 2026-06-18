package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.playfieldportal.core.data.database.entity.ThemeEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ThemeDao {

    @Query("SELECT * FROM themes ORDER BY is_built_in DESC, name ASC")
    fun observeAll(): Flow<List<ThemeEntity>>

    @Query("SELECT * FROM themes WHERE id = :id")
    suspend fun getById(id: String): ThemeEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(theme: ThemeEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(themes: List<ThemeEntity>)

    @Update
    suspend fun update(theme: ThemeEntity)

    @Query("DELETE FROM themes WHERE id = :id AND is_built_in = 0")
    suspend fun deleteUserTheme(id: String)

    @Query("UPDATE themes SET is_active = (id = :id)")
    suspend fun setActiveTheme(id: String)
}
