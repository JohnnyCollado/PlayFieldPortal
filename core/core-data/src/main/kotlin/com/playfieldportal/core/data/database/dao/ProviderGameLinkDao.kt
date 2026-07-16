package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.ProviderGameLinkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ProviderGameLinkDao {

    // A game may hold one link per provider; until the UI grows multi-set surfaces these
    // single-link reads stay deterministic by preferring the first provider alphabetically.
    @Query("SELECT * FROM provider_game_links WHERE game_id = :gameId ORDER BY provider LIMIT 1")
    fun observeForGame(gameId: Long): Flow<ProviderGameLinkEntity?>

    @Query("SELECT * FROM provider_game_links WHERE game_id = :gameId ORDER BY provider LIMIT 1")
    suspend fun getForGame(gameId: Long): ProviderGameLinkEntity?

    /** Every game-provider link, for the batch "sync all" pass. */
    @Query("SELECT * FROM provider_game_links")
    suspend fun getAll(): List<ProviderGameLinkEntity>

    /** Ids of all linked games, for deriving the hub's untracked list. */
    @Query("SELECT game_id FROM provider_game_links")
    fun observeLinkedGameIds(): Flow<List<Long>>

    /** True when any library game links to this provider identity. */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM provider_game_links " +
            "WHERE provider = :provider AND provider_game_id = :providerGameId)"
    )
    suspend fun linkExistsFor(provider: String, providerGameId: String): Boolean

    /** Every link for one provider — the LOCAL_STEAM ownership refresh pass. */
    @Query("SELECT * FROM provider_game_links WHERE provider = :provider")
    suspend fun getByProvider(provider: String): List<ProviderGameLinkEntity>

    /** Writes the derived ownership classification (null = unknown) onto an existing link. */
    @Query(
        "UPDATE provider_game_links SET ownership = :ownership " +
            "WHERE game_id = :gameId AND provider = :provider"
    )
    suspend fun setOwnership(gameId: Long, provider: String, ownership: String?)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(link: ProviderGameLinkEntity)

    @Query("DELETE FROM provider_game_links WHERE game_id = :gameId")
    suspend fun deleteForGame(gameId: Long)
}
