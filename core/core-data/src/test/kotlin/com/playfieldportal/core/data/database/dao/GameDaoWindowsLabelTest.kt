package com.playfieldportal.core.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.PFPDatabase
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.toDomain
import com.playfieldportal.core.data.database.entity.toEntity
import com.playfieldportal.core.domain.model.WindowsGameLabel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Windows game label round-trips through the column, the DAO setter and the mappers. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class GameDaoWindowsLabelTest {

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

    private suspend fun newGame(): Long =
        dao.upsert(
            GameEntity(
                title = "Hellraiser",
                platformId = "windows",
                romPath = null,
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
            ),
        )

    @Test
    fun `setWindowsLabel stores the label and toDomain reads it back`() = runTest {
        val id = newGame()

        dao.setWindowsLabel(id, WindowsGameLabel.NON_STEAM.name)

        assertEquals(WindowsGameLabel.NON_STEAM, dao.getById(id)!!.toDomain().windowsLabel)
    }

    @Test
    fun `clearing the label returns the game to auto`() = runTest {
        val id = newGame()
        dao.setWindowsLabel(id, WindowsGameLabel.LOCAL.name)

        dao.setWindowsLabel(id, null)

        assertNull(dao.getById(id)!!.toDomain().windowsLabel)
    }

    @Test
    fun `a whole-row upsert of the domain game keeps its label`() = runTest {
        val id = newGame()
        dao.setWindowsLabel(id, WindowsGameLabel.STEAM.name)

        // What an importer re-run does: read, copy, write the whole row back.
        val game = dao.getById(id)!!.toDomain().copy(title = "Hellraiser (re-import)")
        dao.upsert(game.toEntity())

        val stored = dao.getById(id)!!.toDomain()
        assertEquals("Hellraiser (re-import)", stored.title)
        assertEquals(WindowsGameLabel.STEAM, stored.windowsLabel)
    }

    @Test
    fun `an unknown stored value reads as auto`() = runTest {
        val id = newGame()

        dao.setWindowsLabel(id, "EPIC_STORE")

        assertNull(dao.getById(id)!!.toDomain().windowsLabel)
    }
}
