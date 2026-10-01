package com.playfieldportal.core.data.repository

import com.playfieldportal.core.data.database.dao.CollectionDao
import com.playfieldportal.core.data.database.dao.CollectionWithCount
import com.playfieldportal.core.data.database.dao.ListStateDao
import io.mockk.coVerify
import io.mockk.mockk
import com.playfieldportal.core.data.database.entity.CollectionEntity
import com.playfieldportal.core.data.database.entity.CollectionGameEntity
import com.playfieldportal.core.data.database.entity.GameEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Repository-level behaviour for user collections. Membership is keyed by gameId, so a game's
 * platform is irrelevant here — adding "an NES game" and "a PSP game" is just two gameIds.
 * SQL-level concerns (content_type filtering for All Games, cascade deletes) live in the DAO/SQL
 * and are exercised by the queries themselves.
 */
class CollectionRepositoryTest {

    private val listStateDao: ListStateDao = mockk(relaxed = true)

    private fun repo(dao: CollectionDao = FakeCollectionDao()) = CollectionRepository(dao, listStateDao)

    // ── Custom memory cards live in one category, ordered within it ───────────

    @Test
    fun `a new card goes to the end of its own category`() = runTest {
        val repo = repo()
        val gamesA = repo.create("A", categoryId = "games")
        val rpgA = repo.create("RPG A", categoryId = "custom_rpg_9")
        val gamesB = repo.create("B", categoryId = "games")

        val byId = repo.getAll().associateBy { it.id }
        assertEquals(0, byId.getValue(gamesA).sortOrder)
        assertEquals(0, byId.getValue(rpgA).sortOrder)
        assertEquals(1, byId.getValue(gamesB).sortOrder)
    }

    @Test
    fun `move swaps only with a neighbour in the same category`() = runTest {
        val repo = repo()
        val a = repo.create("A", categoryId = "games")
        val other = repo.create("Other", categoryId = "custom_rpg_9")
        val b = repo.create("B", categoryId = "games")

        assertTrue(repo.move(b, up = true))

        val games = repo.getAll().filter { it.categoryId == "games" }.sortedBy { it.sortOrder }
        assertEquals(listOf(b, a), games.map { it.id })
        assertEquals(0, repo.getAll().first { it.id == other }.sortOrder)
    }

    @Test
    fun `move at the edge of its category does nothing`() = runTest {
        val repo = repo()
        val only = repo.create("Only", categoryId = "custom_rpg_9")
        repo.create("Elsewhere", categoryId = "games")

        assertFalse(repo.move(only, up = true))
        assertFalse(repo.move(only, up = false))
    }

    @Test
    fun `rehoming moves every card of a category after the target's own, keeping their order`() = runTest {
        val repo = repo()
        val existing = repo.create("Existing", categoryId = "games")
        val first = repo.create("First", categoryId = "custom_ff_5")
        val second = repo.create("Second", categoryId = "custom_ff_5")
        repo.addGame(first, gameId = 3)

        repo.rehomeAll(fromCategoryId = "custom_ff_5", toCategoryId = "games")

        val games = repo.getAll().filter { it.categoryId == "games" }.sortedBy { it.sortOrder }
        assertEquals(listOf(existing, first, second), games.map { it.id })
        assertTrue(repo.getCollectionIdsForGame(3).contains(first))
    }

    @Test
    fun `deleting all cards in a category leaves other categories' cards`() = runTest {
        val repo = repo()
        val kept = repo.create("Kept", categoryId = "games")
        repo.create("Gone 1", categoryId = "custom_ff_5")
        repo.create("Gone 2", categoryId = "custom_ff_5")

        repo.deleteAllIn("custom_ff_5")

        assertEquals(listOf(kept), repo.getAll().map { it.id })
    }

    @Test
    fun `deleting a card forgets its stored order, sort and place in other lists`() = runTest {
        val repo = repo()
        val id = repo.create("Temp")

        repo.delete(id)

        coVerify { listStateDao.deleteLists(listOf("collection:$id")) }
        coVerify { listStateDao.deleteItemEverywhere("collection:$id") }
    }

    @Test
    fun `moving a card to another category drops its place in the old root's order`() = runTest {
        val repo = repo()
        val id = repo.create("Tactics", categoryId = "custom_ff_5")

        repo.setCategory(id, "games")

        coVerify { listStateDao.deleteItemEverywhere("collection:$id") }
        assertEquals("games", repo.getAll().single().categoryId)
    }

    @Test
    fun `a blank name becomes Untitled Custom Card`() = runTest {
        val repo = repo()
        val id = repo.create("   ")

        assertEquals("Untitled Custom Card", repo.getAll().first { it.id == id }.name)
    }

    @Test
    fun `create assigns incrementing sort order and trims name`() = runTest {
        val repo = repo()
        val a = repo.create("  RPGs  ")
        val b = repo.create("Currently Playing")

        val all = repo.getAll()
        assertEquals(2, all.size)
        assertEquals("RPGs", all.first { it.id == a }.name)
        assertTrue(all.first { it.id == a }.sortOrder < all.first { it.id == b }.sortOrder)
    }

    @Test
    fun `rename updates the name`() = runTest {
        val repo = repo()
        val id = repo.create("Old")
        repo.rename(id, "New Name")
        assertEquals("New Name", repo.getAll().first { it.id == id }.name)
    }

    @Test
    fun `delete removes the collection and its memberships`() = runTest {
        val repo = repo()
        val id = repo.create("Temp")
        repo.addGame(id, gameId = 5)
        repo.delete(id)

        assertTrue(repo.getAll().none { it.id == id })
        assertTrue(repo.getCollectionIdsForGame(5).isEmpty())
    }

    @Test
    fun `add games from different platforms to the same collection`() = runTest {
        val repo = repo()
        val id = repo.create("Best Games")
        repo.addGame(id, gameId = 1)   // e.g. an NES game
        repo.addGame(id, gameId = 2)   // e.g. a PSP game

        assertEquals(2, repo.getAll().first { it.id == id }.gameCount)
        assertTrue(repo.getCollectionIdsForGame(1).contains(id))
        assertTrue(repo.getCollectionIdsForGame(2).contains(id))
    }

    @Test
    fun `a game can belong to multiple collections`() = runTest {
        val repo = repo()
        val rpgs = repo.create("RPGs")
        val fav = repo.create("Arcade Night")
        repo.addGame(rpgs, gameId = 7)
        repo.addGame(fav, gameId = 7)

        assertEquals(setOf(rpgs, fav), repo.getCollectionIdsForGame(7).toSet())
    }

    @Test
    fun `remove game drops only that membership`() = runTest {
        val repo = repo()
        val id = repo.create("Mario Games")
        repo.addGame(id, gameId = 3)
        repo.addGame(id, gameId = 4)
        repo.removeGame(id, gameId = 3)

        val members = repo.getCollectionIdsForGame(3)
        assertFalse(members.contains(id))
        assertTrue(repo.getCollectionIdsForGame(4).contains(id))
    }

    @Test
    fun `toggle adds then removes membership`() = runTest {
        val repo = repo()
        val id = repo.create("Currently Playing")

        assertTrue(repo.toggleGame(id, gameId = 9))   // now a member
        assertTrue(repo.getCollectionIdsForGame(9).contains(id))
        assertFalse(repo.toggleGame(id, gameId = 9))  // toggled back off
        assertTrue(repo.getCollectionIdsForGame(9).isEmpty())
    }

    @Test
    fun `adding the same game twice is idempotent`() = runTest {
        val repo = repo()
        val id = repo.create("Dupes")
        repo.addGame(id, gameId = 1)
        repo.addGame(id, gameId = 1)
        assertEquals(1, repo.getAll().first { it.id == id }.gameCount)
    }

    @Test
    fun `move swaps order with the neighbour`() = runTest {
        val repo = repo()
        val a = repo.create("A")
        val b = repo.create("B")

        assertTrue(repo.move(b, up = true))
        val ordered = repo.getAll()
        assertEquals(b, ordered.first().id)
        assertEquals(a, ordered[1].id)
    }
}

// In-memory fake — faithfully models membership semantics without Room.
private class FakeCollectionDao : CollectionDao {
    private var nextId = 1L
    private val collections = linkedMapOf<Long, CollectionEntity>()
    private val memberships = mutableListOf<CollectionGameEntity>()

    private fun ordered() = collections.values.sortedWith(compareBy({ it.sortOrder }, { it.createdAt }))

    override fun observeAllWithCounts(): Flow<List<CollectionWithCount>> = flowOf(countsList())
    override suspend fun getAllWithCounts(): List<CollectionWithCount> = countsList()
    private fun countsList() = ordered().map { c ->
        CollectionWithCount(c, memberships.count { it.collectionId == c.id })
    }

    override suspend fun getAll(): List<CollectionEntity> = ordered()
    override suspend fun getById(id: Long): CollectionEntity? = collections[id]

    override fun observeGames(collectionId: Long): Flow<List<GameEntity>> = flowOf(emptyList())

    override fun observeCollectionIdsForGame(gameId: Long): Flow<List<Long>> =
        flowOf(memberships.filter { it.gameId == gameId }.map { it.collectionId })

    override suspend fun getCollectionIdsForGame(gameId: Long): List<Long> =
        memberships.filter { it.gameId == gameId }.map { it.collectionId }

    override suspend fun getGameIdsInCollection(collectionId: Long): List<Long> =
        memberships.filter { it.collectionId == collectionId }.map { it.gameId }

    override suspend fun getMemberships(collectionId: Long): List<CollectionGameEntity> =
        memberships.filter { it.collectionId == collectionId }

    override suspend fun isGameInCollection(collectionId: Long, gameId: Long): Int =
        memberships.count { it.collectionId == collectionId && it.gameId == gameId }

    override suspend fun maxSortOrder(): Int = collections.values.maxOfOrNull { it.sortOrder } ?: -1

    override suspend fun maxSortOrderIn(categoryId: String): Int =
        collections.values.filter { it.categoryId == categoryId }.maxOfOrNull { it.sortOrder } ?: -1

    override suspend fun getByCategory(categoryId: String): List<CollectionEntity> =
        ordered().filter { it.categoryId == categoryId }

    override suspend fun insert(collection: CollectionEntity): Long {
        val id = nextId++
        collections[id] = collection.copy(id = id)
        return id
    }

    override suspend fun update(collection: CollectionEntity) { collections[collection.id] = collection }

    override suspend fun rename(id: Long, name: String, updatedAt: Long) {
        collections[id]?.let { collections[id] = it.copy(name = name, updatedAt = updatedAt) }
    }

    override suspend fun setSortOrder(id: Long, order: Int) {
        collections[id]?.let { collections[id] = it.copy(sortOrder = order) }
    }

    override suspend fun delete(id: Long) {
        collections.remove(id)
        memberships.removeAll { it.collectionId == id }
    }

    override suspend fun addGame(join: CollectionGameEntity) {
        if (memberships.none { it.collectionId == join.collectionId && it.gameId == join.gameId }) {
            memberships.add(join)
        }
    }

    override suspend fun removeGame(collectionId: Long, gameId: Long) {
        memberships.removeAll { it.collectionId == collectionId && it.gameId == gameId }
    }

    override suspend fun touch(id: Long, updatedAt: Long) {
        collections[id]?.let { collections[id] = it.copy(updatedAt = updatedAt) }
    }

    override suspend fun setCategory(id: Long, categoryId: String, updatedAt: Long) {
        collections[id]?.let { collections[id] = it.copy(categoryId = categoryId, updatedAt = updatedAt) }
    }

    override suspend fun setPinned(id: Long, pinned: Boolean, updatedAt: Long) {
        collections[id]?.let { collections[id] = it.copy(isPinned = pinned, updatedAt = updatedAt) }
    }

    override suspend fun setIcon(id: Long, iconKey: String?, updatedAt: Long) {
        collections[id]?.let { collections[id] = it.copy(iconKey = iconKey, updatedAt = updatedAt) }
    }
}
