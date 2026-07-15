package com.playfieldportal.core.data.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.entity.SteamNoAchievementsEntity
import com.playfieldportal.core.data.database.entity.SteamOwnedGameEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class SteamOwnedGamesDaoTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PFPDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val dao = db.steamOwnedGamesDao()

    @After fun tearDown() = db.close()

    private fun game(appid: String, playtime: Long, synced: Long? = null) = SteamOwnedGameEntity(
        appid = appid, name = "Game $appid",
        playtimeForeverMinutes = playtime, syncedPlaytimeMinutes = synced, fetchedAt = 1L,
    )

    @Test
    fun `replaceOwned refreshes the list but keeps each game's sync bookmark`() = runTest {
        dao.upsertAll(listOf(game("10", playtime = 60, synced = 60), game("20", playtime = 30, synced = 30)))

        // A fresh fetch: game 10 gained playtime, game 20 vanished (refunded), game 30 is new.
        dao.replaceOwned(listOf(game("10", playtime = 90), game("30", playtime = 5)))

        val byId = dao.getAll().associateBy { it.appid }
        assertEquals(2, byId.size)
        assertEquals(90, byId.getValue("10").playtimeForeverMinutes)
        assertEquals(60, byId.getValue("10").syncedPlaytimeMinutes) // bookmark survived
        assertNull(byId.getValue("30").syncedPlaytimeMinutes)
        assertNull(byId["20"])
    }

    @Test
    fun `markSynced bookmarks the current playtime`() = runTest {
        dao.upsertAll(listOf(game("10", playtime = 90)))

        dao.markSynced("10")

        assertEquals(90, dao.syncedPlaytime("10"))
    }

    @Test
    fun `no-achievements memo persists appids`() = runTest {
        dao.rememberNoAchievements(SteamNoAchievementsEntity("55", checkedAt = 1L))
        assertEquals(listOf("55"), dao.noAchievementAppids())
    }
}
