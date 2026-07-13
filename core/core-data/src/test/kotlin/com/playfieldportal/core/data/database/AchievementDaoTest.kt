package com.playfieldportal.core.data.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.entity.AchievementEntity
import com.playfieldportal.core.data.database.entity.AchievementSetEntity
import com.playfieldportal.core.data.database.entity.GameEntity
import kotlinx.coroutines.flow.first
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
class AchievementDaoTest {

    private val db = Room.inMemoryDatabaseBuilder(
        ApplicationProvider.getApplicationContext(),
        PFPDatabase::class.java,
    ).allowMainThreadQueries().build()

    private val sets = db.achievementSetDao()
    private val coins = db.achievementDao()

    @After fun tearDown() = db.close()

    private suspend fun seedGame(): Long = db.gameDao().upsert(
        GameEntity(
            title = "Chrono Trigger",
            platformId = "snes",
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
    fun `wallet aggregate weights earned coins and adds platinum on mastery`() = runTest {
        val gameId = seedGame()
        // 23 bronze, 15 silver, 6 gold earned, not mastered.
        sets.upsert(
            AchievementSetEntity(
                gameId = gameId,
                provider = "RETRO_ACHIEVEMENTS",
                providerGameId = "319",
                bronzeTotal = 24, silverTotal = 16, goldTotal = 7,
                bronzeEarned = 23, silverEarned = 15, goldEarned = 6,
                mastered = false,
            ),
        )
        assertEquals(23 * 15 + 15 * 30 + 6 * 90, sets.observeWalletCoins().first())

        // Mastering the set banks the Platinum's 300 on top of the full individual value.
        sets.upsert(
            AchievementSetEntity(
                gameId = gameId,
                provider = "RETRO_ACHIEVEMENTS",
                providerGameId = "319",
                bronzeTotal = 24, silverTotal = 16, goldTotal = 7,
                bronzeEarned = 24, silverEarned = 16, goldEarned = 7,
                mastered = true,
            ),
        )
        assertEquals(24 * 15 + 16 * 30 + 7 * 90 + 300, sets.observeWalletCoins().first())
    }

    @Test
    fun `coins are observable per game and earned counts are tallied`() = runTest {
        val gameId = seedGame()
        coins.upsertAll(
            listOf(
                AchievementEntity(gameId, "RETRO_ACHIEVEMENTS", "1", "Dream's end", "Beat Lavos", "GOLD", 2.1, isEarned = true, earnedAt = 1_000),
                AchievementEntity(gameId, "RETRO_ACHIEVEMENTS", "2", "Beyond time", "Hardcore win", "BRONZE", 44.0, isEarned = false),
            ),
        )
        assertEquals(2, coins.observeForGame(gameId).first().size)
        assertEquals(1, coins.earnedCount(gameId))
    }

    @Test
    fun `deleting a game cascades to its set and coins`() = runTest {
        val gameId = seedGame()
        sets.upsert(
            AchievementSetEntity(gameId, "STEAM", "1337", bronzeEarned = 1),
        )
        coins.upsertAll(
            listOf(AchievementEntity(gameId, "STEAM", "ACH_WIN", "Winner", "", "BRONZE", 30.0)),
        )

        db.openHelper.writableDatabase.execSQL("DELETE FROM games WHERE id = $gameId")

        assertEquals(0, sets.count())
        assertEquals(0, coins.earnedCount(gameId))
        assertNull(sets.observeForGame(gameId).first())
    }
}
