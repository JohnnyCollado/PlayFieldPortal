package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.PhotoLibraryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PhotoLibraryDao {

    @Query("SELECT * FROM photo_libraries ORDER BY display_name COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<PhotoLibraryEntity>>

    @Query("SELECT * FROM photo_libraries ORDER BY display_name COLLATE NOCASE ASC")
    suspend fun getAll(): List<PhotoLibraryEntity>

    @Query("SELECT * FROM photo_libraries WHERE id = :id")
    suspend fun getById(id: String): PhotoLibraryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(library: PhotoLibraryEntity)

    @Query("DELETE FROM photo_libraries WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE photo_libraries SET display_name = :name, updated_at = :now WHERE id = :id")
    suspend fun setDisplayName(id: String, name: String, now: Long)

    @Query("UPDATE photo_libraries SET tree_uri = :treeUri, updated_at = :now WHERE id = :id")
    suspend fun setTreeUri(id: String, treeUri: String, now: Long)

    @Query("UPDATE photo_libraries SET scan_recursively = :recursive, updated_at = :now WHERE id = :id")
    suspend fun setScanRecursively(id: String, recursive: Boolean, now: Long)

    @Query("UPDATE photo_libraries SET photo_count = :count, last_scanned_at = :scannedAt, updated_at = :scannedAt WHERE id = :id")
    suspend fun updateScanResult(id: String, count: Int, scannedAt: Long)
}
