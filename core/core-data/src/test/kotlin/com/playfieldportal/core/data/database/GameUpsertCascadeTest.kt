package com.playfieldportal.core.data.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.entity.CollectionEntity
import com.playfieldportal.core.data.database.entity.CollectionGameEntity
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.PlaySessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `GameDao.upsert` must never cascade-delete a game's children.
 *
 * It used to be `@Insert(onConflict = REPLACE)`. Room's generated `onOpen` turns
 * `PRAGMA foreign_keys` on, and a REPLACE resolves a conflict by DELETING the existing row — which
 * fires every `ON DELETE CASCADE` against `games.id`. Every save of an existing game (a rescan, a
 * mark-as-game, a disc-set refresh) silently wiped its play sessions, collection membership,
 * achievement links and storefront identities. An existing row is now updated in place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GameUpsertCascadeTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PFPDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val gameDao = db.gameDao()
    private val playSessionDao = db.playSessionDao()
    private val collectionDao = db.collectionDao()

    @After fun tearDown() = db.close()

    private fun game(title: String) = GameEntity(
        title = title,
        platformId = "windows",
        romPath = null,
        packageName = "app.gamenative",
        emulatorPackage = null,
        artworkUri = null,
        heroUri = null,
        logoUri = null,
        description = null,
        developer = null,
        publisher = null,
        releaseYear = null,
        genre = null,
        steamGridDbId = null,
    )

    private suspend fun withChildren(gameId: Long): Long {
        playSessionDao.insert(PlaySessionEntity(gameId = gameId, platformId = "windows", launchedAt = 1L))
        val collectionId = collectionDao.insert(CollectionEntity(name = "Favorites"))
        collectionDao.addGame(CollectionGameEntity(collectionId, gameId))
        return collectionId
    }

    @Test
    fun `upsert onto an existing game id keeps its play sessions and collection membership`() = runTest {
        val gameId = gameDao.upsert(game("Portal 2"))
        val collectionId = withChildren(gameId)

        // A Fill-style upsert on the same id, as PcGameScanner's Fill path issues.
        val returned = gameDao.upsert(game("Portal 2").copy(id = gameId, title = "Portal 2 (rescraped)"))

        assertEquals(gameId, returned)
        assertEquals("Portal 2 (rescraped)", gameDao.getById(gameId)?.title)
        assertEquals(1, playSessionDao.getAll().size)
        assertEquals(listOf(gameId), collectionDao.getGameIdsInCollection(collectionId))
    }

    @Test
    fun `a fresh row for a rom path already in the library updates that game in place`() = runTest {
        val gameId = gameDao.upsert(game("Sonic").copy(platformId = "megadrive", romPath = "/roms/md/sonic.md"))
        val collectionId = withChildren(gameId)

        // A scanner that built a new entity (id 0) for a file it has seen before.
        val returned = gameDao.upsert(
            game("Sonic the Hedgehog").copy(platformId = "megadrive", romPath = "/roms/md/sonic.md"),
        )

        assertEquals(gameId, returned)
        assertEquals(1, gameDao.getAll().size)
        assertEquals("Sonic the Hedgehog", gameDao.getById(gameId)?.title)
        assertEquals(1, playSessionDao.getAll().size)
        assertEquals(listOf(gameId), collectionDao.getGameIdsInCollection(collectionId))
    }

    @Test
    fun `a new game is inserted and gets a new id`() = runTest {
        val first = gameDao.upsert(game("Portal"))
        val second = gameDao.upsert(game("Portal 2"))

        assertTrue(second > first)
        assertEquals(2, gameDao.getAll().size)
    }
}
