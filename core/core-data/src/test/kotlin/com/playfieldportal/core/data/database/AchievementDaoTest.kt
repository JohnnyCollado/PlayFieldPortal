package com.playfieldportal.core.data.database

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.playfieldportal.core.data.database.entity.AccountAchievementEntity
import com.playfieldportal.core.data.database.entity.AccountAchievementSetEntity
import com.playfieldportal.core.data.database.entity.GameEntity
import com.playfieldportal.core.data.database.entity.ProviderGameLinkEntity
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

    private val sets = db.accountAchievementSetDao()
    private val coins = db.accountAchievementDao()
    private val links = db.providerGameLinkDao()

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

    private suspend fun seedLink(gameId: Long, provider: String, providerGameId: String) =
        links.upsert(ProviderGameLinkEntity(gameId, provider, providerGameId, "MANUAL", 0L))

    private fun set(
        provider: String,
        providerGameId: String,
        bronzeEarned: Int = 0, silverEarned: Int = 0, goldEarned: Int = 0,
        bronzeTotal: Int = 0, silverTotal: Int = 0, goldTotal: Int = 0,
        mastered: Boolean = false,
    ) = AccountAchievementSetEntity(
        provider = provider, providerGameId = providerGameId, title = "Chrono Trigger",
        bronzeTotal = bronzeTotal, silverTotal = silverTotal, goldTotal = goldTotal,
        bronzeEarned = bronzeEarned, silverEarned = silverEarned, goldEarned = goldEarned,
        mastered = mastered,
    )

    private fun coin(provider: String, providerGameId: String, id: String, earned: Boolean) =
        AccountAchievementEntity(
            provider = provider, providerGameId = providerGameId, providerAchievementId = id,
            title = id, description = "", tier = "BRONZE", globalRarity = 30.0, isEarned = earned,
        )

    @Test
    fun `wallet aggregate weights earned coins and adds platinum on mastery`() = runTest {
        // 23 bronze, 15 silver, 6 gold earned, not mastered.
        sets.upsert(
            set(
                "RETRO_ACHIEVEMENTS", "319",
                bronzeTotal = 24, silverTotal = 16, goldTotal = 7,
                bronzeEarned = 23, silverEarned = 15, goldEarned = 6,
            ),
        )
        assertEquals(23 * 15 + 15 * 30 + 6 * 90, sets.observeWalletCoins().first())

        // Mastering the set banks the Platinum's 300 on top of the full individual value.
        sets.upsert(
            set(
                "RETRO_ACHIEVEMENTS", "319",
                bronzeTotal = 24, silverTotal = 16, goldTotal = 7,
                bronzeEarned = 24, silverEarned = 16, goldEarned = 7,
                mastered = true,
            ),
        )
        assertEquals(24 * 15 + 16 * 30 + 7 * 90 + 300, sets.observeWalletCoins().first())
    }

    @Test
    fun `game-keyed reads resolve through the provider link`() = runTest {
        val gameId = seedGame()
        seedLink(gameId, "RETRO_ACHIEVEMENTS", "319")
        sets.upsert(set("RETRO_ACHIEVEMENTS", "319", bronzeEarned = 1, bronzeTotal = 2))
        coins.upsertAll(
            listOf(
                coin("RETRO_ACHIEVEMENTS", "319", "1", earned = true),
                coin("RETRO_ACHIEVEMENTS", "319", "2", earned = false),
            ),
        )

        assertEquals(1, sets.observeForGame(gameId).first()?.bronzeEarned)
        assertEquals(2, coins.observeForGame(gameId).first().size)
        assertEquals(1, coins.observeForGame(gameId).first().count { it.isEarned })
    }

    @Test
    fun `deleting a game severs the link but account rows survive`() = runTest {
        val gameId = seedGame()
        seedLink(gameId, "STEAM", "1337")
        sets.upsert(set("STEAM", "1337", bronzeEarned = 1, bronzeTotal = 1))
        coins.upsertAll(listOf(coin("STEAM", "1337", "ACH_WIN", earned = true)))

        db.openHelper.writableDatabase.execSQL("DELETE FROM games WHERE id = $gameId")

        assertNull(sets.observeForGame(gameId).first())
        assertEquals(0, coins.observeForGame(gameId).first().size)
        // Account history outlives library membership: the wallet keeps the earned value.
        assertEquals(15, sets.observeWalletCoins().first())
        assertEquals(1, coins.getForSet("STEAM", "1337").size)
    }

    @Test
    fun `deleteForSet clears one provider's coins and leaves the other's`() = runTest {
        coins.upsertAll(
            listOf(
                coin("STEAM", "1337", "ACH_WIN", earned = true),
                coin("RETRO_ACHIEVEMENTS", "1337", "77", earned = true),
            ),
        )

        coins.deleteForSet("STEAM", "1337")

        assertEquals(0, coins.getForSet("STEAM", "1337").size)
        assertEquals(1, coins.getForSet("RETRO_ACHIEVEMENTS", "1337").size)
    }

    @Test
    fun `hub projections list only sets linked to library games`() = runTest {
        val gameId = seedGame()
        seedLink(gameId, "RETRO_ACHIEVEMENTS", "319")
        sets.upsert(set("RETRO_ACHIEVEMENTS", "319", bronzeEarned = 1, bronzeTotal = 2))
        sets.upsert(set("STEAM", "999", bronzeEarned = 5, bronzeTotal = 5)) // account-only entry

        val rows = sets.observeGameSets().first()

        assertEquals(1, rows.size)
        assertEquals(gameId, rows.single().gameId)
        assertEquals("Chrono Trigger", rows.single().title)
        // The account-only set still counts in the wallet even without a library game.
        assertEquals(15 + 5 * 15, sets.observeWalletCoins().first())
    }
}
