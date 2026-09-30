package com.playfieldportal.core.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.PFPDatabase
import com.playfieldportal.core.data.database.entity.GameEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Choose Disc writes the pick as both the set's primary (what the library shows and launches) and
 * the preferred marker (what lets a scan keep it). Exactly one disc of the set holds each, and
 * other sets are never touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GameDaoPreferredDiscTest {

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
    fun tearDown() {
        db.close()
    }

    private fun disc(title: String, setKey: String, number: Int, primary: Boolean) = GameEntity(
        title = title,
        platformId = "psx",
        romPath = "/roms/psx/$title.cue",
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
        discSetKey = setKey,
        discNumber = number,
        isDiscPrimary = primary,
    )

    @Test
    fun `picking a disc makes it the only primary and the only preferred disc of its set`() = runTest {
        val disc1 = dao.upsert(disc("FF7 (Disc 1)", "ff7", 1, primary = true))
        val disc2 = dao.upsert(disc("FF7 (Disc 2)", "ff7", 2, primary = false))
        val disc3 = dao.upsert(disc("FF7 (Disc 3)", "ff7", 3, primary = false))

        dao.setPreferredDisc(disc1, disc2)
        dao.setPreferredDisc(disc2, disc3)

        val members = dao.getDiscSetMembers("ff7")
        assertEquals(listOf(disc3), members.filter { it.isDiscPrimary }.map { it.id })
        assertEquals(listOf(disc3), members.filter { it.isDiscPreferred }.map { it.id })
    }

    @Test
    fun `picking a disc leaves other sets alone`() = runTest {
        val ff7Disc1 = dao.upsert(disc("FF7 (Disc 1)", "ff7", 1, primary = true))
        val ff7Disc2 = dao.upsert(disc("FF7 (Disc 2)", "ff7", 2, primary = false))
        val pe2Disc1 = dao.upsert(disc("PE2 (Disc 1)", "pe2", 1, primary = true))
        dao.upsert(disc("PE2 (Disc 2)", "pe2", 2, primary = false))

        dao.setPreferredDisc(ff7Disc1, ff7Disc2)

        val pe2 = dao.getDiscSetMembers("pe2")
        assertEquals(listOf(pe2Disc1), pe2.filter { it.isDiscPrimary }.map { it.id })
        assertEquals(emptyList<Long>(), pe2.filter { it.isDiscPreferred }.map { it.id })
    }
}
