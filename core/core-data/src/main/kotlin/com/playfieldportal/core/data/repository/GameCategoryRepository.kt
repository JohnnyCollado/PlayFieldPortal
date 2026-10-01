package com.playfieldportal.core.data.repository

import com.playfieldportal.core.data.database.dao.CategoryDao
import com.playfieldportal.core.data.database.dao.CollectionDao
import com.playfieldportal.core.data.database.entity.CategoryItemEntity
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * One row of a gaming category's Memory Card. [cardNames] are the category's custom memory cards
 * the game sits in; empty means it was put in the category directly ("loose").
 */
data class CategoryCardGame(
    val game: Game,
    val cardNames: List<String>,
    // The latest time the game was put in the category, directly or onto one of its cards.
    // 0 when unknown (a row older than the column).
    val addedAt: Long = 0L,
)

private const val ITEM_TYPE_GAME = "game"

// Manages assignment of games to gaming categories via the CategoryItemEntity junction table.
// Custom memory cards (collections) are NOT tracked there — a card belongs to exactly one category
// via CollectionEntity.categoryId. The junction table is games-only (echo/copy model: a game may
// appear in several gaming categories), and a game's pin now lives in list_items.
@Singleton
class GameCategoryRepository @Inject constructor(
    private val gameRepository: GameRepository,
    private val categoryDao: CategoryDao,
    private val collectionDao: CollectionDao,
) {
    // Emits whenever category item assignments change (games in any category)
    // We watch all app items as a proxy since item_type differentiates; items are stored together
    fun changes(): Flow<Unit> =
        categoryDao.observeAppItems().map { }

    /**
     * Every game in the category — put there directly or sitting in one of its custom memory
     * cards — once each, ordered by title. A disc set appears once as its primary; a game whose
     * file is missing does not appear.
     */
    suspend fun categoryCardGames(categoryId: String): List<CategoryCardGame> {
        // gameId → the custom cards it sits in (empty for a loose game), in first-seen order,
        // and the latest time it was added to the category by either route.
        val sources = linkedMapOf<Long, MutableList<String>>()
        val addedAt = mutableMapOf<Long, Long>()
        categoryDao.getItemsForCategory(categoryId)
            .filter { it.itemType == ITEM_TYPE_GAME }
            .forEach { row ->
                val gameId = row.itemId.toLongOrNull() ?: return@forEach
                sources.getOrPut(gameId) { mutableListOf() }
                addedAt[gameId] = maxOf(addedAt[gameId] ?: 0L, row.addedAt)
            }
        collectionDao.getByCategory(categoryId).forEach { card ->
            collectionDao.getMemberships(card.id).forEach { membership ->
                sources.getOrPut(membership.gameId) { mutableListOf() }.add(card.name)
                addedAt[membership.gameId] = maxOf(addedAt[membership.gameId] ?: 0L, membership.addedAt)
            }
        }

        val rows = linkedMapOf<String, CategoryCardGame>()
        for ((gameId, cardNames) in sources) {
            val game = gameRepository.getById(gameId) ?: continue
            val setKey = game.discSetKey
            val (key, display) = if (setKey == null) {
                if (game.isMissing) continue
                "game:$gameId" to game
            } else {
                val members = gameRepository.getDiscSetMembers(setKey).ifEmpty { listOf(game) }
                val present = members.filterNot { it.isMissing }
                if (present.isEmpty()) continue
                "set:$setKey" to (members.firstOrNull { it.isDiscPrimary } ?: present.first())
            }
            val names = (rows[key]?.cardNames.orEmpty() + cardNames).distinct()
            val added = maxOf(rows[key]?.addedAt ?: 0L, addedAt[gameId] ?: 0L)
            rows[key] = CategoryCardGame(display, names, added)
        }
        return rows.values.sortedBy { it.game.displayTitle.lowercase() }
    }

    /** Ids of the games put in the category directly (not through a custom card). */
    suspend fun looseGameIds(categoryId: String): List<Long> =
        categoryDao.getItemsForCategory(categoryId)
            .filter { it.itemType == ITEM_TYPE_GAME }
            .mapNotNull { it.itemId.toLongOrNull() }

    suspend fun addGameToCategory(gameId: Long, categoryId: String) {
        categoryDao.addItem(CategoryItemEntity(categoryId, gameId.toString(), ITEM_TYPE_GAME))
        Timber.i("Game $gameId added to category $categoryId")
    }

    suspend fun removeGameFromCategory(gameId: Long, categoryId: String) {
        categoryDao.removeItem(categoryId, gameId.toString())
        Timber.i("Game $gameId removed from category $categoryId")
    }

    /**
     * Takes the game out of the category entirely: its direct membership AND its place in every
     * custom memory card of this category. Memberships belong to a logical game, so every disc of
     * its set is cleared — the disc that holds the row is not always the one on screen.
     */
    suspend fun removeGameFromCategoryEverywhere(gameId: Long, categoryId: String) {
        val setKey = gameRepository.getById(gameId)?.discSetKey
        val ids = setKey?.let { key -> gameRepository.getDiscSetMembers(key).map { it.id } }
            .orEmpty().ifEmpty { listOf(gameId) }
        val cards = collectionDao.getByCategory(categoryId)
        ids.forEach { id ->
            categoryDao.removeItem(categoryId, id.toString())
            cards.forEach { card ->
                if (id in collectionDao.getGameIdsInCollection(card.id)) collectionDao.removeGame(card.id, id)
            }
        }
        Timber.i("Game $gameId removed from category $categoryId and its custom cards")
    }

    suspend fun moveGameToCategory(gameId: Long, fromCategoryId: String, toCategoryId: String) {
        removeGameFromCategoryEverywhere(gameId, fromCategoryId)
        addGameToCategory(gameId, toCategoryId)
        Timber.i("Game $gameId moved from $fromCategoryId to $toCategoryId")
    }
}
