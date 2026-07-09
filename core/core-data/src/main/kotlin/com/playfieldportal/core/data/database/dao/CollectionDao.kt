package com.playfieldportal.core.data.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.playfieldportal.core.data.database.entity.CollectionEntity
import com.playfieldportal.core.data.database.entity.CollectionGameEntity
import com.playfieldportal.core.data.database.entity.GameEntity
import kotlinx.coroutines.flow.Flow

// Collection + its current game count, used to render the collection list without N queries.
data class CollectionWithCount(
    @Embedded val collection: CollectionEntity,
    val game_count: Int,
)

@Dao
interface CollectionDao {

    @Query(
        """
        SELECT c.*, (
            SELECT COUNT(*) FROM collection_games cg WHERE cg.collection_id = c.id
        ) AS game_count
        FROM collections c
        ORDER BY c.sort_order ASC, c.created_at ASC
        """
    )
    fun observeAllWithCounts(): Flow<List<CollectionWithCount>>

    @Query(
        """
        SELECT c.*, (
            SELECT COUNT(*) FROM collection_games cg WHERE cg.collection_id = c.id
        ) AS game_count
        FROM collections c
        ORDER BY c.sort_order ASC, c.created_at ASC
        """
    )
    suspend fun getAllWithCounts(): List<CollectionWithCount>

    @Query("SELECT * FROM collections ORDER BY sort_order ASC, created_at ASC")
    suspend fun getAll(): List<CollectionEntity>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun getById(id: Long): CollectionEntity?

    // Games in a collection, in the order they were added (oldest first).
    @Query(
        """
        SELECT g.* FROM games g
        INNER JOIN collection_games cg ON cg.game_id = g.id
        WHERE cg.collection_id = :collectionId
        ORDER BY cg.added_at ASC
        """
    )
    fun observeGames(collectionId: Long): Flow<List<GameEntity>>

    // Which collections a given game belongs to — drives the checkmarks in "Add to Collection".
    @Query("SELECT collection_id FROM collection_games WHERE game_id = :gameId")
    fun observeCollectionIdsForGame(gameId: Long): Flow<List<Long>>

    @Query("SELECT collection_id FROM collection_games WHERE game_id = :gameId")
    suspend fun getCollectionIdsForGame(gameId: Long): List<Long>

    @Query("SELECT game_id FROM collection_games WHERE collection_id = :collectionId")
    suspend fun getGameIdsInCollection(collectionId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM collection_games WHERE collection_id = :collectionId AND game_id = :gameId")
    suspend fun isGameInCollection(collectionId: Long, gameId: Long): Int

    @Query("SELECT COALESCE(MAX(sort_order), -1) FROM collections")
    suspend fun maxSortOrder(): Int

    @Insert
    suspend fun insert(collection: CollectionEntity): Long

    @Update
    suspend fun update(collection: CollectionEntity)

    @Query("UPDATE collections SET name = :name, updated_at = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, name: String, updatedAt: Long)

    @Query("UPDATE collections SET sort_order = :order WHERE id = :id")
    suspend fun setSortOrder(id: Long, order: Int)

    @Query("UPDATE collections SET category_id = :categoryId, updated_at = :updatedAt WHERE id = :id")
    suspend fun setCategory(id: Long, categoryId: String, updatedAt: Long)

    @Query("UPDATE collections SET is_pinned = :pinned, updated_at = :updatedAt WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, updatedAt: Long)

    @Query("UPDATE collections SET icon_key = :iconKey, updated_at = :updatedAt WHERE id = :id")
    suspend fun setIcon(id: Long, iconKey: String?, updatedAt: Long)

    @Query("DELETE FROM collections WHERE id = :id")
    suspend fun delete(id: Long)

    // Re-adding an existing membership is a no-op (composite PK + IGNORE).
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addGame(join: CollectionGameEntity)

    @Query("DELETE FROM collection_games WHERE collection_id = :collectionId AND game_id = :gameId")
    suspend fun removeGame(collectionId: Long, gameId: Long)

    @Query("UPDATE collections SET updated_at = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)
}
