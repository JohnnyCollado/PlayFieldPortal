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

/**
 * Settles task 1.1 (docs/plans/artwork-revisions-merge-fixes-plan.md §3): does
 * `GameDao.upsert`'s `@Insert(onConflict = REPLACE)` cascade-delete a game's children when it
 * replaces an existing row?
 *
 * Room's generated `onOpen` runs `PRAGMA foreign_keys = ON` unconditionally, including for this
 * Robolectric in-memory database (`PFPDatabase_Impl.onOpen`, generated). Per SQLite's REPLACE
 * conflict-resolution semantics, a REPLACE that deletes a conflicting row fires that row's foreign
 * key actions once `foreign_keys` is on, regardless of `recursive_triggers` (that pragma only gates
 * DELETE *triggers*, not FK actions). `play_sessions` and `collection_games` both declare
 * `ON DELETE CASCADE` against `games.id`. So an upsert that replaces an existing game id is expected
 * to delete its play sessions and collection membership before the replacement row is inserted.
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

    @Test
    fun `upsert onto an existing game id cascade-deletes its play sessions and collection membership`() = runTest {
        val gameId = gameDao.upsert(game("Portal 2"))
        playSessionDao.insert(PlaySessionEntity(gameId = gameId, platformId = "windows", launchedAt = 1L))
        val collectionId = collectionDao.insert(CollectionEntity(name = "Favorites"))
        collectionDao.addGame(CollectionGameEntity(collectionId, gameId))

        // A Fill-style REPLACE upsert on the same id, as PcGameScanner's Fill path issues today.
        gameDao.upsert(game("Portal 2").copy(id = gameId, title = "Portal 2 (rescraped)"))

        assertEquals(0, playSessionDao.getAll().size)
        assertEquals(0, collectionDao.getGameIdsInCollection(collectionId).size)
    }
}
