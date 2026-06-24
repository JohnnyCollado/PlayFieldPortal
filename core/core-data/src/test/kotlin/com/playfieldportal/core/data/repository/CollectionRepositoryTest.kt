package com.playfieldportal.core.data.repository

import com.playfieldportal.core.data.database.dao.CollectionDao
import com.playfieldportal.core.data.database.dao.CollectionWithCount
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

    private fun repo(dao: CollectionDao = FakeCollectionDao()) = CollectionRepository(dao)

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

    override suspend fun isGameInCollection(collectionId: Long, gameId: Long): Int =
        memberships.count { it.collectionId == collectionId && it.gameId == gameId }

    override suspend fun maxSortOrder(): Int = collections.values.maxOfOrNull { it.sortOrder } ?: -1

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
}
