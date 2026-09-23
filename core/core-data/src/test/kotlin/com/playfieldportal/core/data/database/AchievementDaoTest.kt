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
    // Account-wide projections read confirmed identities only (selective sync, Task 4).
    private val tracking = db.achievementTrackingDao()
    private suspend fun confirm(provider: String, providerGameId: String) =
        tracking.confirm(provider, providerGameId, "Chrono Trigger", now = 1L)

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
        confirm("RETRO_ACHIEVEMENTS", "319")
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
        confirm("STEAM", "1337")
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
    fun `an earlier set becomes the library game's row once its match is confirmed`() = runTest {
        // A set that landed before the game existed in the library (e.g. a Local Steam folder).
        sets.upsert(
            set("STEAM", "220", bronzeEarned = 3, bronzeTotal = 10)
                .copy(title = "Half-Life 2", lastSyncedAt = 1L),
        )
        assertEquals(0, sets.observeAccountSets().first().size) // unconfirmed: not tracked

        // The game arrives, links to the same provider identity, and the match is confirmed.
        val gameId = seedGame()
        seedLink(gameId, "STEAM", "220")
        confirm("STEAM", "220")

        // One row — carrying the in-library marker and the library game's title.
        val after = sets.observeAccountSets().first().single()
        assertEquals(gameId, after.libraryGameId)
        assertEquals("Chrono Trigger", after.title)
        assertEquals(3, after.bronzeEarned)
        assertEquals(3, sets.observeForGame(gameId).first()?.bronzeEarned)
        assertEquals(3 * 15, sets.observeWalletCoins().first())
    }

    @Test
    fun `hub projection lists every account set with its optional library game`() = runTest {
        val gameId = seedGame()
        seedLink(gameId, "RETRO_ACHIEVEMENTS", "319")
        confirm("RETRO_ACHIEVEMENTS", "319")
        confirm("LOCAL_STEAM", "999")
        sets.upsert(set("RETRO_ACHIEVEMENTS", "319", bronzeEarned = 1, bronzeTotal = 2))
        sets.upsert(set("LOCAL_STEAM", "999", bronzeEarned = 5, bronzeTotal = 5)) // folder-only entry

        val rows = sets.observeAccountSets().first().associateBy { it.providerGameId }

        assertEquals(2, rows.size)
        assertEquals(gameId, rows.getValue("319").libraryGameId)
        assertEquals("Chrono Trigger", rows.getValue("319").title)
        assertNull(rows.getValue("999").libraryGameId)
        assertEquals(15 + 5 * 15, sets.observeWalletCoins().first())
    }
}
