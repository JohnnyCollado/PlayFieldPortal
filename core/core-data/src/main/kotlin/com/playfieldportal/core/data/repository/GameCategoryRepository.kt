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
            .filter { it.itemType == ITEM_TYPE_GAME }
        val games = rows.mapNotNull { row ->
            row.itemId.toLongOrNull()?.let { id ->
                gameRepository.getById(id)?.let { game -> row to game }
            }
        }

        // A category membership belongs to a logical game, not to a physical disc. If any disc in
        // a set is assigned, project the set's primary (or the first available member) once. The
        // pinned state follows the set when a non-primary member was the one pinned.
        val projected = mutableListOf<GameCategoryItem>()
        val seenSets = mutableSetOf<String>()
        for ((row, game) in games) {
            val setKey = game.discSetKey
            if (setKey == null) {
                if (!game.isMissing) projected += GameCategoryItem.GameItem(game, row.pinned)
                continue
            }
            if (!seenSets.add(setKey)) continue
            val members = gameRepository.getDiscSetMembers(setKey).ifEmpty { listOf(game) }
            val present = members.filterNot { it.isMissing }
            if (present.isEmpty()) continue
            val display = members.firstOrNull { it.isDiscPrimary }
                ?: present.firstOrNull()
                ?: continue
            val pinned = games.any { (memberRow, member) ->
                memberRow.pinned && member.discSetKey == setKey
            }
            projected += GameCategoryItem.GameItem(display, pinned)
        }

        return projected.sortedWith(compareByDescending<GameCategoryItem> { it.pinned }.thenBy { it.title })
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
