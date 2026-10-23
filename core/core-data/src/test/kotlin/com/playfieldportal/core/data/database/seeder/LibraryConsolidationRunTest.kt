package com.playfieldportal.core.data.database.seeder

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.PFPDatabase
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.PlaySessionEntity
import com.playfieldportal.core.domain.model.GameContentType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Merging duplicate Windows games keeps everything that points at the deleted row.
 *
 * Survey L3 (docs/audits/codebase-survey-2026-10-22.md): the merge called `deleteById(loser)`
 * after copying only collection memberships, so the loser's play sessions (and achievement links,
 * storefront identities, launch outcomes, UMD slots, artwork records) were cascade-deleted.
 * `GameDao.mergeRows` re-points all of them first.
 *
 * One test method only: the DataStore behind the consolidation's done-flag is process-wide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class LibraryConsolidationRunTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PFPDatabase::class.java,
    ).allowMainThreadQueries().build()

    @After fun tearDown() = db.close()

    private fun windowsGame(shortcutId: String?) = GameEntity(
        title = "Hades", platformId = "windows", romPath = null,
        packageName = "banner.hub", emulatorPackage = null,
        artworkUri = null, heroUri = null, logoUri = null,
        description = null, developer = null, publisher = null, releaseYear = null,
        genre = null, steamGridDbId = null,
        contentType = GameContentType.GAME.name,
        launchShortcutId = shortcutId,
    )

    @Test
    fun `the merged-away duplicate's play sessions move to the survivor`() = runTest {
        val gameDao = db.gameDao()
        val sessions = db.playSessionDao()
        val survivorId = gameDao.upsert(windowsGame(shortcutId = "sc-1"))
        val loserId = gameDao.upsert(windowsGame(shortcutId = null))
        sessions.insert(PlaySessionEntity(gameId = loserId, platformId = "windows", launchedAt = 1L))
        sessions.insert(PlaySessionEntity(gameId = loserId, platformId = "windows", launchedAt = 2L))

        LibraryConsolidation(
            ApplicationProvider.getApplicationContext(),
            gameDao, db.collectionDao(), db.memoryCardDao(),
        ).run()

        assertEquals(listOf(survivorId), gameDao.getAll().map { it.id })
        assertEquals(listOf(survivorId, survivorId), sessions.getAll().map { it.gameId })
    }
}
