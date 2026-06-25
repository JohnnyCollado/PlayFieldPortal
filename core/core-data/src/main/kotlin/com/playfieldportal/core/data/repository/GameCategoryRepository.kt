package com.playfieldportal.core.data.repository

import com.playfieldportal.core.data.database.dao.CategoryDao
import com.playfieldportal.core.data.database.entity.CategoryItemEntity
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.model.GameCollection
import com.playfieldportal.core.domain.repository.GameRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// Represents a game or collection assigned to a gaming category
sealed class GameCategoryItem {
    abstract val id: String
    abstract val title: String
    abstract val pinned: Boolean

    data class GameItem(
        val game: Game,
        override val pinned: Boolean = false,
    ) : GameCategoryItem() {
        override val id: String = game.id.toString()
        override val title: String = game.displayTitle
    }

    data class CollectionItem(
        val collection: GameCollection,
        override val pinned: Boolean = false,
    ) : GameCategoryItem() {
        override val id: String = "col_${collection.id}"
        override val title: String = collection.name
    }
}

private const val ITEM_TYPE_GAME = "game"
private const val ITEM_TYPE_COLLECTION = "collection"

// Manages assignment of games and collections to gaming categories.
// Uses CategoryItemEntity junction table with item_type to distinguish game vs collection items.
@Singleton
class GameCategoryRepository @Inject constructor(
    private val gameRepository: GameRepository,
    private val collectionRepository: CollectionRepository,
    private val categoryDao: CategoryDao,
) {
    // Emits whenever category item assignments change (games or collections in any category)
    // We watch all app items as a proxy since item_type differentiates; items are stored together
    fun changes(): Flow<Unit> =
        categoryDao.observeAppItems().map { Unit }

    // Resolves all games/collections assigned to a gaming category, sorted with pinned first
    suspend fun itemsForCategory(categoryId: String): List<GameCategoryItem> {
        val rows = categoryDao.getItemsForCategory(categoryId)
        val items = mutableListOf<GameCategoryItem>()

        for (row in rows) {
            when (row.itemType) {
                ITEM_TYPE_GAME -> {
                    val gameId = row.itemId.toLongOrNull()
                    if (gameId != null) {
                        gameRepository.getById(gameId)?.let { game ->
                            items.add(GameCategoryItem.GameItem(game, row.pinned))
                        }
                    }
                }
                ITEM_TYPE_COLLECTION -> {
                    val collectionId = row.itemId.toLongOrNull()
                    if (collectionId != null) {
                        val collections = collectionRepository.getAll()
                        val collection = collections.firstOrNull { it.id == collectionId }
                        if (collection != null) {
                            items.add(GameCategoryItem.CollectionItem(collection, row.pinned))
                        }
                    }
                }
            }
        }

        return items.sortedWith(compareByDescending<GameCategoryItem> { it.pinned }.thenBy { it.title })
    }

    suspend fun addGameToCategory(gameId: Long, categoryId: String) {
        categoryDao.addItem(CategoryItemEntity(categoryId, gameId.toString(), ITEM_TYPE_GAME))
        Timber.i("Game $gameId added to category $categoryId")
    }

    suspend fun addCollectionToCategory(collectionId: Long, categoryId: String) {
        categoryDao.addItem(CategoryItemEntity(categoryId, collectionId.toString(), ITEM_TYPE_COLLECTION))
        Timber.i("Collection $collectionId added to category $categoryId")
    }

    suspend fun removeGameFromCategory(gameId: Long, categoryId: String) {
        categoryDao.removeItem(categoryId, gameId.toString())
        Timber.i("Game $gameId removed from category $categoryId")
    }

    suspend fun removeCollectionFromCategory(collectionId: Long, categoryId: String) {
        categoryDao.removeItem(categoryId, collectionId.toString())
        Timber.i("Collection $collectionId removed from category $categoryId")
    }

    suspend fun moveGameToCategory(gameId: Long, fromCategoryId: String, toCategoryId: String) {
        removeGameFromCategory(gameId, fromCategoryId)
        addGameToCategory(gameId, toCategoryId)
        Timber.i("Game $gameId moved from $fromCategoryId to $toCategoryId")
    }

    suspend fun moveCollectionToCategory(collectionId: Long, fromCategoryId: String, toCategoryId: String) {
        removeCollectionFromCategory(collectionId, fromCategoryId)
        addCollectionToCategory(collectionId, toCategoryId)
        Timber.i("Collection $collectionId moved from $fromCategoryId to $toCategoryId")
    }

    suspend fun pinGameInCategory(gameId: Long, categoryId: String, pinned: Boolean) {
        categoryDao.setItemPinned(categoryId, gameId.toString(), pinned)
        Timber.i("Game $gameId pinned=$pinned in category $categoryId")
    }

    suspend fun pinCollectionInCategory(collectionId: Long, categoryId: String, pinned: Boolean) {
        categoryDao.setItemPinned(categoryId, collectionId.toString(), pinned)
        Timber.i("Collection $collectionId pinned=$pinned in category $categoryId")
    }
}
