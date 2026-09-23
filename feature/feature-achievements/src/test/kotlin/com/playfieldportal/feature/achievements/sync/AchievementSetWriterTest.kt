package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.database.dao.AccountAchievementDao
import com.playfieldportal.core.data.database.dao.AccountAchievementSetDao
import com.playfieldportal.core.data.database.entity.AccountAchievementEntity
import com.playfieldportal.core.data.database.entity.AccountAchievementSetEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.achievements.api.SyncedCoin
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Selective sync, Task 4/5: every successful fetch lands through one writer. The set summary and
 * its coin rows are replaced in one transaction (an interrupted write can't expose an empty set),
 * the tier rules carry over from the old repository path, and the writer reports whether earned
 * progress actually changed so an unchanged check is never presented as new achievements.
 */
class AchievementSetWriterTest {

    private val setDao = mockk<AccountAchievementSetDao>()
    private val coinDao = mockk<AccountAchievementDao>()
    private val credentials = mockk<AchievementCredentialsProvider>(relaxed = true)
    private val writer = AchievementSetWriter(setDao, coinDao, credentials, clock = { NOW })

    private val setSlot = slot<AccountAchievementSetEntity>()
    private val coinsSlot = slot<List<AccountAchievementEntity>>()

    init {
        coEvery { setDao.getSet(any(), any()) } returns null
        coEvery { coinDao.getForSet(any(), any()) } returns emptyList()
        coEvery { setDao.replaceSet(capture(setSlot), capture(coinsSlot)) } just Runs
    }

    private val steam440 = AchievementIdentity(AchievementProvider.STEAM, "440")

    private fun coin(id: String, tier: ShibaTier, earned: Boolean, description: String = "", hidden: Boolean = false) =
        SyncedCoin(id, id, description, tier, 5.0, null, isHidden = hidden, isEarned = earned, earnedHardcore = earned, earnedAt = if (earned) 1L else null)

    private fun stored(id: String, tier: String, earned: Boolean, description: String = "") = AccountAchievementEntity(
        provider = "STEAM", providerGameId = "440", providerAchievementId = id, title = id,
        description = description, tier = tier, globalRarity = 9.0, isEarned = earned,
        earnedAt = if (earned) 1L else null,
    )

    @Test
    fun `the set and its coins are replaced together with per-tier counts`() = runTest {
        val changed = writer.write(
            steam440, "Team Fortress 2",
            listOf(coin("g1", ShibaTier.GOLD, true), coin("b1", ShibaTier.BRONZE, false), coin("b2", ShibaTier.BRONZE, true)),
        )

        assertTrue(changed)
        coVerify(exactly = 1) { setDao.replaceSet(any(), any()) }
        with(setSlot.captured) {
            assertEquals("Team Fortress 2", title)
            assertEquals(2, bronzeTotal)
            assertEquals(1, bronzeEarned)
            assertEquals(1, goldEarned)
            assertFalse(mastered)
            assertEquals(NOW, lastSyncedAt)
        }
        assertEquals(3, coinsSlot.captured.size)
        coVerify { credentials.setLastSyncedAt(NOW) }
    }

    @Test
    fun `a provider Platinum is the crown and stays out of the tallies`() = runTest {
        writer.write(steam440, "TF2", listOf(coin("p1", ShibaTier.PLATINUM, true), coin("b1", ShibaTier.BRONZE, false)))

        assertTrue(setSlot.captured.mastered)
        assertEquals(1, setSlot.captured.bronzeTotal)
    }

    @Test
    fun `every coin earned masters a set without a Platinum`() = runTest {
        writer.write(steam440, "TF2", listOf(coin("g1", ShibaTier.GOLD, true), coin("b1", ShibaTier.BRONZE, true)))

        assertTrue(setSlot.captured.mastered)
    }

    @Test
    fun `a blank title keeps the stored title and icon`() = runTest {
        coEvery { setDao.getSet("STEAM", "440") } returns AccountAchievementSetEntity(
            provider = "STEAM", providerGameId = "440", title = "Stored", iconUrl = "https://icon", lastSyncedAt = 1L,
        )

        writer.write(steam440, "", listOf(coin("b1", ShibaTier.BRONZE, true)))

        assertEquals("Stored", setSlot.captured.title)
        assertEquals("https://icon", setSlot.captured.iconUrl)
    }

    @Test
    fun `a Steam re-sync within seven days keeps each coin's stored tier`() = runTest {
        coEvery { setDao.getSet("STEAM", "440") } returns
            AccountAchievementSetEntity(provider = "STEAM", providerGameId = "440", title = "TF2", lastSyncedAt = NOW - 60_000)
        coEvery { coinDao.getForSet("STEAM", "440") } returns listOf(stored("g1", "GOLD", earned = false))

        writer.write(steam440, "TF2", listOf(coin("g1", ShibaTier.SILVER, false)))

        assertEquals("GOLD", coinsSlot.captured.single().tier)
    }

    @Test
    fun `identical earned state is reported as unchanged`() = runTest {
        coEvery { setDao.getSet("STEAM", "440") } returns
            AccountAchievementSetEntity(provider = "STEAM", providerGameId = "440", title = "TF2", lastSyncedAt = 1L)
        coEvery { coinDao.getForSet("STEAM", "440") } returns listOf(stored("g1", "GOLD", earned = true), stored("b1", "BRONZE", earned = false))

        val changed = writer.write(steam440, "TF2", listOf(coin("g1", ShibaTier.GOLD, true), coin("b1", ShibaTier.BRONZE, false)))

        assertFalse(changed)
    }

    @Test
    fun `a newly earned coin is reported as a change`() = runTest {
        coEvery { setDao.getSet("STEAM", "440") } returns
            AccountAchievementSetEntity(provider = "STEAM", providerGameId = "440", title = "TF2", lastSyncedAt = 1L)
        coEvery { coinDao.getForSet("STEAM", "440") } returns listOf(stored("b1", "BRONZE", earned = false))

        assertTrue(writer.write(steam440, "TF2", listOf(coin("b1", ShibaTier.BRONZE, true))))
    }

    @Test
    fun `a known hidden description is carried over when the fetch withholds it`() = runTest {
        coEvery { coinDao.getForSet("STEAM", "440") } returns listOf(stored("s1", "GOLD", earned = true, description = "Found it"))

        writer.write(steam440, "TF2", listOf(coin("s1", ShibaTier.GOLD, true, description = "", hidden = true)))

        assertEquals("Found it", coinsSlot.captured.single().description)
    }

    private companion object {
        const val NOW = 50_000_000L
    }
}
