package com.playfieldportal.core.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.PFPDatabase
import com.playfieldportal.core.data.database.entity.CollectionEntity
import com.playfieldportal.core.data.database.entity.CollectionGameEntity
import com.playfieldportal.core.data.database.entity.GameEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Multi-disc projection (docs/plans/multi-disc-games-plan.md step 5): the display queries show one
 * row per disc set — the primary — so platform lists, All Games, Favorites and platform counts
 * count a set once, while the unprojected queries still return every row for scan baselines and
 * per-disc achievement matching.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GameDaoProjectionTest {

    private lateinit var db: PFPDatabase
    private lateinit var dao: GameDao
    private lateinit var collectionDao: CollectionDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PFPDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.gameDao()
        collectionDao = db.collectionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun game(
        title: String,
        platformId: String,
        romPath: String,
        discSetKey: String? = null,
        discNumber: Int? = null,
        isDiscPrimary: Boolean = false,
        isFavorite: Boolean = false,
        contentType: String = "GAME",
        isMissing: Boolean = false,
    ) = GameEntity(
        title = title,
        platformId = platformId,
        romPath = romPath,
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
        discSetKey = discSetKey,
        discNumber = discNumber,
        isDiscPrimary = isDiscPrimary,
        isFavorite = isFavorite,
        contentType = contentType,
        isMissing = isMissing,
    )

    private val psxSetKey = "psx\u0001/roms/psx\u0001Final Fantasy VII"

    @Test
    fun `platform list projects one row per set`() = runTest {
        dao.upsert(game("Final Fantasy VII (Disc 1)", "psx", "/roms/psx/ff7-1.cue", psxSetKey, 1, true))
        dao.upsert(game("Final Fantasy VII (Disc 2)", "psx", "/roms/psx/ff7-2.cue", psxSetKey, 2, false))
        dao.upsert(game("Final Fantasy VII (Disc 3)", "psx", "/roms/psx/ff7-3.cue", psxSetKey, 3, false))
        dao.upsert(game("Chrono Trigger", "psx", "/roms/psx/ct.cue"))

        val projected = dao.observePlatformGames("psx").first()

        assertEquals(listOf("Chrono Trigger", "Final Fantasy VII (Disc 1)"), projected.map { it.title }.sorted())
    }

    @Test
    fun `all games projects one row per set`() = runTest {
        dao.upsert(game("Final Fantasy VII (Disc 1)", "psx", "/roms/psx/ff7-1.cue", psxSetKey, 1, true))
        dao.upsert(game("Final Fantasy VII (Disc 2)", "psx", "/roms/psx/ff7-2.cue", psxSetKey, 2, false))
        dao.upsert(game("Chrono Trigger", "psx", "/roms/psx/ct.cue"))
        dao.upsert(game("Panzer Dragoon (Disc 1)", "saturn", "/roms/saturn/pd-1.cue", "saturn\u0001/roms/saturn\u0001Panzer Dragoon", 1, true))
        dao.upsert(game("Panzer Dragoon (Disc 2)", "saturn", "/roms/saturn/pd-2.cue", "saturn\u0001/roms/saturn\u0001Panzer Dragoon", 2, false))
        dao.upsert(game("Angry Birds", "android", "/app/angry", contentType = "ANDROID_APP"))

        val projected = dao.observeAllGames().first()

        assertEquals(
            setOf("Chrono Trigger", "Final Fantasy VII (Disc 1)", "Panzer Dragoon (Disc 1)"),
            projected.map { it.title }.toSet(),
        )
    }

    @Test
    fun `favorites projects one row per set`() = runTest {
        dao.upsert(game("Final Fantasy VII (Disc 1)", "psx", "/roms/psx/ff7-1.cue", psxSetKey, 1, true, isFavorite = true))
        dao.upsert(game("Final Fantasy VII (Disc 2)", "psx", "/roms/psx/ff7-2.cue", psxSetKey, 2, false, isFavorite = true))

        val favorites = dao.observeFavorites().first()

        assertEquals(listOf("Final Fantasy VII (Disc 1)"), favorites.map { it.title })
    }

    @Test
    fun `platform count counts a set once`() = runTest {
        dao.upsert(game("Final Fantasy VII (Disc 1)", "psx", "/roms/psx/ff7-1.cue", psxSetKey, 1, true))
        dao.upsert(game("Final Fantasy VII (Disc 2)", "psx", "/roms/psx/ff7-2.cue", psxSetKey, 2, false))
        dao.upsert(game("Final Fantasy VII (Disc 3)", "psx", "/roms/psx/ff7-3.cue", psxSetKey, 3, false))
        dao.upsert(game("Chrono Trigger", "psx", "/roms/psx/ct.cue"))

        assertEquals(2, dao.countGamesByPlatform("psx"))
    }

    @Test
    fun `observeAll projects sets for display counts`() = runTest {
        dao.upsert(game("Final Fantasy VII (Disc 1)", "psx", "/roms/psx/ff7-1.cue", psxSetKey, 1, true))
        dao.upsert(game("Final Fantasy VII (Disc 2)", "psx", "/roms/psx/ff7-2.cue", psxSetKey, 2, false))
        dao.upsert(game("Chrono Trigger", "psx", "/roms/psx/ct.cue"))

        assertEquals(setOf("Chrono Trigger", "Final Fantasy VII (Disc 1)"), dao.observeAll().first().map { it.title }.toSet())
    }

    @Test
    fun `partial set remains visible and missing bucket stays empty`() = runTest {
        dao.upsert(game("Final Fantasy VII", "psx", "/roms/psx/ff7-1.cue", psxSetKey, 1, true, isMissing = true))
        dao.upsert(game("Final Fantasy VII", "psx", "/roms/psx/ff7-2.cue", psxSetKey, 2, false, isMissing = false))
        dao.upsert(game("Final Fantasy VII", "psx", "/roms/psx/ff7-3.cue", psxSetKey, 3, false, isMissing = true))

        assertEquals(1, dao.observePlatformGames("psx").first().size)
        assertEquals(0, dao.observeMissing().first().size)
    }

    @Test
    fun `fully missing set projects one primary into missing bucket`() = runTest {
        dao.upsert(game("Final Fantasy VII", "psx", "/roms/psx/ff7-1.cue", psxSetKey, 1, true, isMissing = true))
        dao.upsert(game("Final Fantasy VII", "psx", "/roms/psx/ff7-2.cue", psxSetKey, 2, false, isMissing = true))
        dao.upsert(game("Final Fantasy VII", "psx", "/roms/psx/ff7-3.cue", psxSetKey, 3, false, isMissing = true))

        assertEquals(0, dao.observePlatformGames("psx").first().size)
        assertEquals(listOf("Final Fantasy VII"), dao.observeMissing().first().map { it.title })
    }

    @Test
    fun `collection projects a secondary membership to the primary once`() = runTest {
        val primaryId = dao.upsert(game("Final Fantasy VII", "psx", "/roms/psx/ff7-1.cue", psxSetKey, 1, true))
        val secondaryId = dao.upsert(game("Final Fantasy VII", "psx", "/roms/psx/ff7-2.cue", psxSetKey, 2, false))
        val collectionId = collectionDao.insert(CollectionEntity(name = "RPGs"))
        collectionDao.addGame(CollectionGameEntity(collectionId, secondaryId))

        val projected = collectionDao.observeGames(collectionId).first()

        assertEquals(listOf(primaryId), projected.map { it.id })
    }

    @Test
    fun `collection count treats a partial set as one logical game`() = runTest {
        val primaryId = dao.upsert(game("Final Fantasy VII", "psx", "/roms/psx/ff7-1.cue", psxSetKey, 1, true, isMissing = true))
        val secondaryId = dao.upsert(game("Final Fantasy VII", "psx", "/roms/psx/ff7-2.cue", psxSetKey, 2, false))
        val collectionId = collectionDao.insert(CollectionEntity(name = "RPGs"))
        collectionDao.addGame(CollectionGameEntity(collectionId, primaryId))
        collectionDao.addGame(CollectionGameEntity(collectionId, secondaryId))

        assertEquals(1, collectionDao.getAllWithCounts().single().game_count)
        assertEquals(listOf(primaryId), collectionDao.observeGames(collectionId).first().map { it.id })
    }

    @Test
    fun `unprojected queries still return every row for baselines and per-disc matching`() = runTest {
        dao.upsert(game("Final Fantasy VII (Disc 1)", "psx", "/roms/psx/ff7-1.cue", psxSetKey, 1, true))
        dao.upsert(game("Final Fantasy VII (Disc 2)", "psx", "/roms/psx/ff7-2.cue", psxSetKey, 2, false))
        dao.upsert(game("Chrono Trigger", "psx", "/roms/psx/ct.cue"))

        // Scan baselines / existing-path resolution must see every disc.
        assertEquals(3, dao.observeByPlatform("psx").first().size)
        // Per-disc achievement matching must see every game row.
        assertEquals(
            setOf("Chrono Trigger", "Final Fantasy VII (Disc 1)", "Final Fantasy VII (Disc 2)"),
            dao.observeGamesOnly().first().map { it.title }.toSet(),
        )
    }
}
