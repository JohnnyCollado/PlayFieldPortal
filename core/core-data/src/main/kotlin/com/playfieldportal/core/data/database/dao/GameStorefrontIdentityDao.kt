package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.playfieldportal.core.data.database.entity.GameStorefrontIdentityEntity

/**
 * Reads and writes `game_storefront_identities` (C23 T6).
 *
 * Deliberately small: a resolver only ever asks "what do I already know about this game", writes
 * one row when it learns something, and deletes one when the user unlinks it. There is no update
 * of a single column, because a re-link replaces the whole fact.
 */
@Dao
interface GameStorefrontIdentityDao {

    /** Every store this game is linked on — what a Rematch screen lists. */
    @Query("SELECT * FROM game_storefront_identities WHERE game_id = :gameId")
    suspend fun forGame(gameId: Long): List<GameStorefrontIdentityEntity>

    /** The identity on one store, or null when the resolver has never linked this game there. */
    @Query("SELECT * FROM game_storefront_identities WHERE game_id = :gameId AND store = :store")
    suspend fun get(gameId: Long, store: String): GameStorefrontIdentityEntity?

    /**
     * Every game already linked to this (store, id). Used to notice that two library rows resolved
     * to the same store entry — a duplicate import, not a reason to refuse the link.
     */
    @Query("SELECT * FROM game_storefront_identities WHERE store = :store AND store_id = :storeId")
    suspend fun byStoreId(store: String, storeId: String): List<GameStorefrontIdentityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(identity: GameStorefrontIdentityEntity)

    @Query("UPDATE game_storefront_identities SET last_verified_at = :at WHERE game_id = :gameId AND store = :store")
    suspend fun markVerified(gameId: Long, store: String, at: Long)

    /** Unlinks one store. The user's other links and every metadata column are untouched. */
    @Query("DELETE FROM game_storefront_identities WHERE game_id = :gameId AND store = :store")
    suspend fun delete(gameId: Long, store: String)

    @Query("DELETE FROM game_storefront_identities WHERE game_id = :gameId")
    suspend fun deleteForGame(gameId: Long)

    @Query("SELECT COUNT(*) FROM game_storefront_identities")
    suspend fun count(): Int
}
