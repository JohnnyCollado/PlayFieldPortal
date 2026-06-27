package com.playfieldportal.core.data.repository

import com.playfieldportal.core.data.database.dao.CategoryDao
import com.playfieldportal.core.data.database.entity.CategoryItemEntity
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

// Represents a game assigned to a gaming category. Collections are NOT tracked here — a
// collection belongs to exactly one category via CollectionEntity.categoryId (its single
// source of truth). The junction table is games-only (echo/copy model: a game may appear
// in several gaming categories).
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
}

private const val ITEM_TYPE_GAME = "game"

// Manages assignment of games to gaming categories via the CategoryItemEntity junction table.
@Singleton
class GameCategoryRepository @Inject constructor(
    private val gameRepository: GameRepository,
    private val categoryDao: CategoryDao,
) {
    // Emits whenever category item assignments change (games in any category)
    // We watch all app items as a proxy since item_type differentiates; items are stored together
    fun changes(): Flow<Unit> =
        categoryDao.observeAppItems().map { Unit }

    // Resolves all games assigned to a gaming category, sorted with pinned first.
    suspend fun itemsForCategory(categoryId: String): List<GameCategoryItem> {
        val rows = categoryDao.getItemsForCategory(categoryId)
        val items = mutableListOf<GameCategoryItem>()

        for (row in rows) {
            if (row.itemType == ITEM_TYPE_GAME) {
                val gameId = row.itemId.toLongOrNull()
                if (gameId != null) {
                    gameRepository.getById(gameId)?.let { game ->
                        items.add(GameCategoryItem.GameItem(game, row.pinned))
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

    suspend fun removeGameFromCategory(gameId: Long, categoryId: String) {
        categoryDao.removeItem(categoryId, gameId.toString())
        Timber.i("Game $gameId removed from category $categoryId")
    }

    suspend fun moveGameToCategory(gameId: Long, fromCategoryId: String, toCategoryId: String) {
        removeGameFromCategory(gameId, fromCategoryId)
        addGameToCategory(gameId, toCategoryId)
        Timber.i("Game $gameId moved from $fromCategoryId to $toCategoryId")
    }

    suspend fun pinGameInCategory(gameId: Long, categoryId: String, pinned: Boolean) {
        categoryDao.setItemPinned(categoryId, gameId.toString(), pinned)
        Timber.i("Game $gameId pinned=$pinned in category $categoryId")
    }
}
