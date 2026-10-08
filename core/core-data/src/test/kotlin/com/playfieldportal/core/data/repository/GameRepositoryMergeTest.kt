package com.playfieldportal.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.PFPDatabase
import com.playfieldportal.core.data.database.entity.CollectionEntity
import com.playfieldportal.core.data.database.entity.CollectionGameEntity
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.PlaySessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Merging one game row into another (a stale `.bin` companion into its `.cue`): the survivor keeps
 * everything worth keeping from the loser, every table that points at the loser follows it to the
 * survivor, and only then is the loser deleted, so its cascades take nothing with them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GameRepositoryMergeTest {

    private lateinit var db: PFPDatabase
    private lateinit var repository: GameRepositoryImpl

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PFPDatabase::class.java,
        ).allowMainThreadQueries().build()
        repository = GameRepositoryImpl(db.gameDao(), db.playSessionDao(), db.platformDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(path: String) = GameEntity(
        title = "Parasite Eve II",
        platformId = "psx",
        romPath = path,
        packageName = null,
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
    fun `the survivor keeps the loser's favorite, play time and links and the loser is gone`() = runTest {
        val dao = db.gameDao()
        val cue = dao.upsert(row("/roms/psx/PE2 (Disc 2)/PE2 (Disc 2).cue").copy(totalPlayTimeMillis = 1_000))
        val bin = dao.upsert(
            row("/roms/psx/PE2 (Disc 2)/PE2 (Disc 2).bin").copy(
                isFavorite = true,
                totalPlayTimeMillis = 5_000,
                lastPlayedAt = 42L,
                userNote = "boss at the end",
            ),
        )
        db.playSessionDao().insert(PlaySessionEntity(gameId = bin, platformId = "psx", launchedAt = 42L, durationMillis = 5_000))
        val shelf = db.collectionDao().insert(CollectionEntity(name = "Horror"))
        db.collectionDao().addGame(CollectionGameEntity(collectionId = shelf, gameId = bin))

        repository.mergeInto(survivorId = cue, loserId = bin)

        assertNull(dao.getById(bin))
        val merged = dao.getById(cue)!!
        assertTrue(merged.isFavorite)
        assertEquals(6_000L, merged.totalPlayTimeMillis)
        assertEquals(42L, merged.lastPlayedAt)
        assertEquals("boss at the end", merged.userNote)
        assertEquals("/roms/psx/PE2 (Disc 2)/PE2 (Disc 2).cue", merged.romPath)
        assertEquals(listOf(cue), db.playSessionDao().getAll().map { it.gameId })
        assertEquals(listOf(shelf), db.collectionDao().getCollectionIdsForGame(cue))
    }

    @Test
    fun `a link both rows hold is kept once, on the survivor`() = runTest {
        val dao = db.gameDao()
        val cue = dao.upsert(row("/roms/psx/Game.cue"))
        val bin = dao.upsert(row("/roms/psx/Game.bin"))
        val shelf = db.collectionDao().insert(CollectionEntity(name = "RPGs"))
        db.collectionDao().addGame(CollectionGameEntity(collectionId = shelf, gameId = cue))
        db.collectionDao().addGame(CollectionGameEntity(collectionId = shelf, gameId = bin))

        repository.mergeInto(survivorId = cue, loserId = bin)

        assertEquals(listOf(shelf), db.collectionDao().getCollectionIdsForGame(cue))
        assertNull(dao.getById(bin))
    }

    @Test
    fun `merging a row that is already gone changes nothing`() = runTest {
        val dao = db.gameDao()
        val cue = dao.upsert(row("/roms/psx/Game.cue"))

        repository.mergeInto(survivorId = cue, loserId = 999L)

        assertEquals("/roms/psx/Game.cue", dao.getById(cue)!!.romPath)
    }
}
