package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.ArtworkImportReportEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ArtworkImportReportDao {

    @Insert
    suspend fun insert(report: ArtworkImportReportEntity): Long

    @Query("SELECT * FROM artwork_import_reports ORDER BY started_at DESC")
    fun observeAll(): Flow<List<ArtworkImportReportEntity>>

    @Query("SELECT * FROM artwork_import_reports WHERE id = :id")
    suspend fun getById(id: Long): ArtworkImportReportEntity?

    @Query("DELETE FROM artwork_import_reports WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM artwork_import_reports")
    suspend fun clear()
}
