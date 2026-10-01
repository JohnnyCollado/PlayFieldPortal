package com.playfieldportal.core.data.repository

import com.playfieldportal.core.data.database.dao.CategoryDao
import com.playfieldportal.core.data.database.dao.CollectionDao
import com.playfieldportal.core.data.database.entity.CategoryItemEntity
import com.playfieldportal.core.data.database.entity.CollectionEntity
import com.playfieldportal.core.data.database.entity.CollectionGameEntity
import com.playfieldportal.core.domain.model.Game
import com.playfieldportal.core.domain.repository.GameRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A gaming category's Memory Card: every game in the category, whether it was put there directly
 * ("loose") or sits in one of the category's custom memory cards. Each game appears once, a disc
 * set appears once as its primary, and a game whose file is gone does not appear.
 */
class GameCategoryRepositoryTest {

    private val category = "custom_ff_5"
    private val gameRepository: GameRepository = mockk()
    private val categoryDao: CategoryDao = mockk(relaxed = true)
    private val collectionDao: CollectionDao = mockk(relaxed = true)
    private val repo = GameCategoryRepository(gameRepository, categoryDao, collectionDao)

    private fun game(
        id: Long,
        title: String,
        discSetKey: String? = null,
        primary: Boolean = false,
        missing: Boolean = false,
    ) = Game(
        id = id,
        title = title,
        platformId = "psx",
        romPath = "/roms/$id.cue",
        discSetKey = discSetKey,
        isDiscPrimary = primary,
        isMissing = missing,
    )

    private fun card(id: Long, name: String) = CollectionEntity(
        id = id, name = name, categoryId = category, createdAt = 1, updatedAt = 1,
    )

    private fun given(
        loose: List<Long> = emptyList(),
        cards: Map<CollectionEntity, List<Long>> = emptyMap(),
        games: List<Game>,
        // When each game was added: to the category directly, and to a card (by card id).
        looseAddedAt: Map<Long, Long> = emptyMap(),
        cardAddedAt: Map<Pair<Long, Long>, Long> = emptyMap(),
    ) {
        coEvery { categoryDao.getItemsForCategory(category) } returns
            loose.map { CategoryItemEntity(category, it.toString(), "game", addedAt = looseAddedAt[it] ?: 0L) }
        coEvery { collectionDao.getByCategory(category) } returns cards.keys.toList()
        cards.forEach { (c, ids) ->
            coEvery { collectionDao.getGameIdsInCollection(c.id) } returns ids
            coEvery { collectionDao.getMemberships(c.id) } returns
                ids.map { CollectionGameEntity(c.id, it, addedAt = cardAddedAt[c.id to it] ?: 0L) }
        }
        coEvery { gameRepository.getById(any()) } answers { games.firstOrNull { it.id == firstArg<Long>() } }
        coEvery { gameRepository.getDiscSetMembers(any()) } answers {
            games.filter { it.discSetKey == firstArg<String>() }
        }
    }

    @Test
    fun `the card lists loose games and custom card members together`() = runTest {
        given(
            loose = listOf(1),
            cards = mapOf(card(10, "Tactics") to listOf(2)),
            games = listOf(game(1, "Final Fantasy VI"), game(2, "Final Fantasy Tactics")),
        )

        val result = repo.categoryCardGames(category).associateBy { it.game.id }

        assertEquals(setOf(1L, 2L), result.keys)
        assertEquals(emptyList(), result.getValue(1).cardNames)
        assertEquals(listOf("Tactics"), result.getValue(2).cardNames)
    }

    @Test
    fun `a game that is both loose and in custom cards appears once and names its cards`() = runTest {
        given(
            loose = listOf(1),
            cards = mapOf(card(10, "Tactics") to listOf(1), card(11, "Mainline") to listOf(1)),
            games = listOf(game(1, "Final Fantasy VI")),
        )

        val result = repo.categoryCardGames(category)

        assertEquals(1, result.size)
        assertEquals(listOf("Tactics", "Mainline"), result.single().cardNames)
    }

    @Test
    fun `a disc set appears once as its primary whichever disc was added`() = runTest {
        given(
            loose = listOf(21),
            cards = mapOf(card(10, "Tactics") to listOf(22)),
            games = listOf(
                game(20, "FF7 Disc 1", discSetKey = "ff7", primary = true),
                game(21, "FF7 Disc 2", discSetKey = "ff7"),
                game(22, "FF7 Disc 3", discSetKey = "ff7"),
            ),
        )

        val result = repo.categoryCardGames(category)

        assertEquals(listOf(20L), result.map { it.game.id })
        assertEquals(listOf("Tactics"), result.single().cardNames)
    }

    @Test
    fun `missing games and deleted games are left out`() = runTest {
        given(
            loose = listOf(1, 2, 99),
            games = listOf(game(1, "Present"), game(2, "Gone", missing = true)),
        )

        assertEquals(listOf(1L), repo.categoryCardGames(category).map { it.game.id })
    }

    @Test
    fun `a row's added time is the latest time the game was put in the category`() = runTest {
        given(
            loose = listOf(1, 2),
            cards = mapOf(card(10, "Tactics") to listOf(1)),
            games = listOf(game(1, "Final Fantasy VI"), game(2, "Final Fantasy IX")),
            looseAddedAt = mapOf(1L to 100L, 2L to 300L),
            cardAddedAt = mapOf((10L to 1L) to 900L),
        )

        val addedAt = repo.categoryCardGames(category).associate { it.game.id to it.addedAt }

        // Game 1 was added directly at 100, then onto a card at 900: the later one counts.
        assertEquals(mapOf(1L to 900L, 2L to 300L), addedAt)
    }

    @Test
    fun `games are ordered by title`() = runTest {
        given(
            loose = listOf(1, 2, 3),
            games = listOf(game(1, "Zelda"), game(2, "chrono trigger"), game(3, "Mario")),
        )

        assertEquals(listOf(2L, 3L, 1L), repo.categoryCardGames(category).map { it.game.id })
    }

    @Test
    fun `removing a game from the category also takes it out of the category's custom cards`() = runTest {
        given(
            loose = listOf(1),
            cards = mapOf(card(10, "Tactics") to listOf(1), card(11, "Mainline") to listOf(2)),
            games = listOf(game(1, "Final Fantasy VI"), game(2, "Final Fantasy IX")),
        )

        repo.removeGameFromCategoryEverywhere(gameId = 1, categoryId = category)

        coVerify { categoryDao.removeItem(category, "1") }
        coVerify { collectionDao.removeGame(10, 1) }
        coVerify(exactly = 0) { collectionDao.removeGame(11, any()) }
    }

    @Test
    fun `a disc set is removed whichever of its discs holds the membership`() = runTest {
        given(
            cards = mapOf(card(10, "Tactics") to listOf(22)),
            games = listOf(
                game(20, "FF7 Disc 1", discSetKey = "ff7", primary = true),
                game(22, "FF7 Disc 3", discSetKey = "ff7"),
            ),
        )

        repo.removeGameFromCategoryEverywhere(gameId = 20, categoryId = category)

        coVerify { collectionDao.removeGame(10, 22) }
        coVerify { categoryDao.removeItem(category, "20") }
        coVerify { categoryDao.removeItem(category, "22") }
    }
}
