package com.playfieldportal.core.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.PFPDatabase
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.toDomain
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A scrape may NAME a game but never RENAME one.
 *
 * `updateMetadata` takes the opposite side for every other column (`COALESCE(:new, old)` — the
 * incoming value wins), which is right for a description and wrong for a title: it let a Change
 * Match, or any later re-scrape, silently rewrite what the library calls a game. The title now
 * only changes through a path the user drove, so the automatic scrape writes it through
 * [GameDao.fillScrapedTitleIfMissing] instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GameDaoTitleWriteTest {

    private lateinit var db: PFPDatabase
    private lateinit var dao: GameDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            PFPDatabase::class.java,
        ).allowMainThreadQueries().build()
        dao = db.gameDao()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun newGame(scrapedTitle: String? = null, override: String? = null): Long =
        dao.upsert(
            GameEntity(
                title = "Sonic_The_Hedgehog_USA",
                platformId = "megadrive",
                romPath = "/roms/md/sonic.md",
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
                scrapedTitle = scrapedTitle,
                userTitleOverride = override,
            ),
        )

    @Test
    fun `fills the title when the game has none`() = runTest {
        val id = newGame()

        dao.fillScrapedTitleIfMissing(id, "Sonic the Hedgehog")

        assertEquals("Sonic the Hedgehog", dao.getById(id)?.scrapedTitle)
    }

    @Test
    fun `leaves an existing title alone - a re-match cannot rename the game`() = runTest {
        val id = newGame(scrapedTitle = "Sonic the Hedgehog")

        dao.fillScrapedTitleIfMissing(id, "Sonic The Hedgehog (Rev A)")

        assertEquals("Sonic the Hedgehog", dao.getById(id)?.scrapedTitle)
    }

    @Test
    fun `the display title survives a re-match`() = runTest {
        val id = newGame(scrapedTitle = "Sonic the Hedgehog")

        dao.fillScrapedTitleIfMissing(id, "Something Else Entirely")

        assertEquals("Sonic the Hedgehog", dao.getById(id)?.toDomain()?.displayTitle)
    }

    @Test
    fun `a user override still outranks the scraped title`() = runTest {
        val id = newGame(override = "My Sonic")

        dao.fillScrapedTitleIfMissing(id, "Sonic the Hedgehog")

        val game = dao.getById(id)!!.toDomain()
        // The column may be filled — it was empty — but it is not what the library shows.
        assertEquals("My Sonic", game.displayTitle)
    }

    @Test
    fun `an empty library title still falls back to the scan name`() = runTest {
        val id = newGame()

        assertNull(dao.getById(id)?.scrapedTitle)
        assertEquals("Sonic_The_Hedgehog_USA", dao.getById(id)!!.toDomain().displayTitle)
    }

    @Test
    fun `the user-driven path can still overwrite the title outright`() = runTest {
        val id = newGame(scrapedTitle = "Sonic the Hedgehog")

        // What the metadata preview's chosen fields go through — deliberately NOT fill-only.
        dao.updateScrapedTitle(id, "Sonic The Hedgehog (Rev A)")

        assertEquals("Sonic The Hedgehog (Rev A)", dao.getById(id)?.scrapedTitle)
    }
}
