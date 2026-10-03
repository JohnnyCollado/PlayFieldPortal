package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.data.database.dao.AccountAchievementDao
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.feature.achievements.provider.localsteam.LocalEarnedRead
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamSource
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LocalSteamCheckStrategyTest {

    private val source = mockk<LocalSteamSource>()
    private val coinDao = mockk<AccountAchievementDao>()
    private val strategy = LocalSteamCheckStrategy(source, coinDao)

    private val identity = AchievementIdentity(AchievementProvider.LOCAL_STEAM, "367520")
    private val entry = TrackedEntry(
        identity = identity, title = "Hollow Knight", lastCheckedAt = 1L, lastDetailAt = 1L,
        retryAt = null, snapshot = "same", storedEarned = 3, storedTotal = 63,
    )

    init {
        // The progress file has not changed since the last sync.
        coEvery { source.readEarned("367520") } returns LocalEarnedRead.Read(emptyList(), "same")
        coEvery { coinDao.hasBlankHiddenDescription("LOCAL_STEAM", "367520") } returns true
    }

    @Test
    fun `a manual update fetches an unchanged game with a blank hidden description`() = runTest {
        val plan = strategy.plan(listOf(entry), SyncTrigger.MANUAL, now = 2L)

        assertEquals(setOf(identity), plan.toFetch)
        assertEquals(emptySet(), plan.unchanged)
    }

    @Test
    fun `an automatic update leaves an unchanged game alone even with a blank hidden description`() = runTest {
        val plan = strategy.plan(listOf(entry), SyncTrigger.AUTOMATIC, now = 2L)

        assertEquals(setOf(identity), plan.unchanged)
        assertEquals(emptySet(), plan.toFetch)
    }

    @Test
    fun `a manual update leaves a game with every hidden description stored alone`() = runTest {
        coEvery { coinDao.hasBlankHiddenDescription("LOCAL_STEAM", "367520") } returns false

        val plan = strategy.plan(listOf(entry), SyncTrigger.MANUAL, now = 2L)

        assertEquals(setOf(identity), plan.unchanged)
    }

    @Test
    fun `a changed game is fetched as before`() = runTest {
        val plan = strategy.plan(listOf(entry.copy(snapshot = "older")), SyncTrigger.AUTOMATIC, now = 2L)

        assertEquals(setOf(identity), plan.toFetch)
    }
}
