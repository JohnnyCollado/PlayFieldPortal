package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.ProviderGameLinkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderGameLinkDao {

    @Query("SELECT * FROM provider_game_links WHERE game_id = :gameId")
    fun observeForGame(gameId: Long): Flow<ProviderGameLinkEntity?>

    @Query("SELECT * FROM provider_game_links WHERE game_id = :gameId")
    suspend fun getForGame(gameId: Long): ProviderGameLinkEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: ProviderGameLinkEntity)

    @Query("DELETE FROM provider_game_links WHERE game_id = :gameId")
    suspend fun deleteForGame(gameId: Long)
}
