package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.data.database.entity.AchievementProviderSyncStateEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.feature.achievements.provider.retro.RaProgressSnapshot
import com.playfieldportal.feature.achievements.provider.retro.RaRemoteDataSource
import com.playfieldportal.feature.achievements.provider.retro.RaSummaryResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Selective sync, Task 6: RetroAchievements is checked summary-first. An eligible day costs one
 * GetUserRecentlyPlayedGames call plus that day's GetUserProgress cohort (one-seventh of the present
 * matched ids, in URL-safe batches); full GetGameInfoAndUserProgress detail is requested only for a
 * new match or a changed summary. Seven active days cover every present id, and missed days are
 * resumed one cohort at a time rather than in a burst.
 */
class RaCheckStrategyTest {

    private val remote = mockk<RaRemoteDataSource>()
    private val store = mockk<AchievementSyncStore>(relaxed = true)
    private val strategy = RaCheckStrategy(remote, store)

    private var cohortState: AchievementProviderSyncStateEntity? = null

    init {
        coEvery { remote.recentlyPlayed(any()) } returns RaSummaryResult.Success(emptyMap())
        coEvery { remote.userProgress(any()) } answers {
            RaSummaryResult.Success(firstArg<List<String>>().associateWith { SAME })
        }
        coEvery { store.providerState(RA) } answers { cohortState }
        val next = slot<Int>()
        val day = slot<Long>()
        coEvery { store.recordCohort(RA, capture(next), capture(day)) } answers {
            cohortState = AchievementProviderSyncStateEntity(
                provider = RA.name, cohortNext = next.captured, cohortLastDay = day.captured,
            )
        }
    }

    private fun entry(id: String, snapshot: String? = SAME.encode(), lastDetailAt: Long? = 1L) = TrackedEntry(
        identity = AchievementIdentity(RA, id),
        title = "Game $id",
        lastCheckedAt = 1L,
        lastDetailAt = lastDetailAt,
        retryAt = null,
        snapshot = snapshot,
        storedEarned = null,
        storedTotal = null,
    )

    private fun ids(cohort: Int, count: Int) = (1..count).map { (it * 7 + cohort).toString() }

    private fun dayMillis(day: Long) = day * DAY + 1_000

    @Test
    fun `an unchanged day costs one recent call plus one cohort batch and no detail`() = runTest {
        val entries = (0..6).flatMap { c -> ids(c, 3) }.map { entry(it) }

        val plan = strategy.plan(entries, SyncTrigger.AUTOMATIC, dayMillis(100))

        assertTrue(plan.toFetch.isEmpty())
        coVerify(exactly = 1) { remote.recentlyPlayed(50) }
        coVerify(exactly = 1) { remote.userProgress(any()) }
        assertEquals(3, plan.unchanged.size)
    }

    @Test
    fun `a changed hardcore count schedules exactly that game's detail`() = runTest {
        val cohortIds = ids(0, 3)
        val changedId = cohortIds[1]
        coEvery { remote.userProgress(any()) } answers {
            RaSummaryResult.Success(
                firstArg<List<String>>().associateWith { if (it == changedId) SAME.copy(numAchievedHardcore = 4) else SAME },
            )
        }

        val plan = strategy.plan(cohortIds.map { entry(it) }, SyncTrigger.AUTOMATIC, dayMillis(100))

        assertEquals(setOf(AchievementIdentity(RA, changedId)), plan.toFetch)
        assertEquals(SAME.copy(numAchievedHardcore = 4).encode(), plan.snapshots[AchievementIdentity(RA, changedId)])
    }

    @Test
    fun `the recent list flags a changed present game and ignores games not on this device`() = runTest {
        coEvery { remote.recentlyPlayed(any()) } returns RaSummaryResult.Success(
            mapOf("14" to SAME.copy(numAchieved = 12), "99999" to SAME.copy(numAchieved = 1)),
        )
        // "14" is cohort 0; today runs cohort 3 so only the recent list can see it.
        cohortState = AchievementProviderSyncStateEntity(provider = RA.name, cohortNext = 3, cohortLastDay = 99)

        val plan = strategy.plan(listOf(entry("14")), SyncTrigger.AUTOMATIC, dayMillis(100))

        assertEquals(setOf(AchievementIdentity(RA, "14")), plan.toFetch)
        assertTrue(plan.toFetch.none { it.providerGameId == "99999" })
    }

    @Test
    fun `seven active days give every present id one summary check`() = runTest {
        val entries = (0..6).flatMap { c -> ids(c, 2) }.map { entry(it) }
        val checked = mutableSetOf<String>()
        coEvery { remote.userProgress(any()) } answers {
            checked += firstArg<List<String>>()
            RaSummaryResult.Success(firstArg<List<String>>().associateWith { SAME })
        }

        for (day in 100L..106L) strategy.plan(entries, SyncTrigger.AUTOMATIC, dayMillis(day))

        assertEquals(entries.map { it.identity.providerGameId }.toSet(), checked)
        coVerify(exactly = 7) { remote.userProgress(any()) }
    }

    @Test
    fun `a second check on the same day does not run another cohort`() = runTest {
        val entries = (0..6).flatMap { c -> ids(c, 2) }.map { entry(it) }

        strategy.plan(entries, SyncTrigger.MANUAL, dayMillis(100))
        strategy.plan(entries, SyncTrigger.MANUAL, dayMillis(100) + HOUR)

        coVerify(exactly = 1) { remote.userProgress(any()) }
        coVerify(exactly = 2) { remote.recentlyPlayed(any()) }
    }

    @Test
    fun `missed days resume with the next cohort only, never a burst`() = runTest {
        cohortState = AchievementProviderSyncStateEntity(provider = RA.name, cohortNext = 2, cohortLastDay = 90)
        val entries = (0..6).flatMap { c -> ids(c, 2) }.map { entry(it) }
        val requested = mutableListOf<List<String>>()
        coEvery { remote.userProgress(any()) } answers {
            requested += firstArg<List<String>>()
            RaSummaryResult.Success(firstArg<List<String>>().associateWith { SAME })
        }

        strategy.plan(entries, SyncTrigger.AUTOMATIC, dayMillis(100))

        assertEquals(listOf(ids(2, 2)), requested)
        coVerify { store.recordCohort(RA, 3, 100) }
    }

    @Test
    fun `a large cohort is split into batches of at most fifty ids`() = runTest {
        val sizes = mutableListOf<Int>()
        coEvery { remote.userProgress(any()) } answers {
            sizes += firstArg<List<String>>().size
            RaSummaryResult.Success(firstArg<List<String>>().associateWith { SAME })
        }

        strategy.plan(ids(0, 120).map { entry(it) }, SyncTrigger.AUTOMATIC, dayMillis(100))

        assertEquals(120, sizes.sum())
        assertTrue(sizes.all { it <= 50 }, "batch sizes $sizes")
    }

    @Test
    fun `a URI-too-long answer halves the batch and retries`() = runTest {
        val sizes = mutableListOf<Int>()
        coEvery { remote.userProgress(any()) } answers {
            val batch = firstArg<List<String>>()
            sizes += batch.size
            if (batch.size > 10) RaSummaryResult.UriTooLong
            else RaSummaryResult.Success(batch.associateWith { SAME })
        }

        val plan = strategy.plan(ids(0, 40).map { entry(it) }, SyncTrigger.AUTOMATIC, dayMillis(100))

        assertEquals(40, plan.unchanged.size)
        assertTrue(sizes.first() > 10)
        coVerify { store.recordCohort(RA, 1, 100) }
    }

    @Test
    fun `a new match is fetched in full without a summary request for it`() = runTest {
        val requested = mutableListOf<String>()
        coEvery { remote.userProgress(any()) } answers {
            requested += firstArg<List<String>>()
            RaSummaryResult.Success(firstArg<List<String>>().associateWith { SAME })
        }

        val plan = strategy.plan(
            listOf(entry("7", snapshot = null, lastDetailAt = null), entry("14")),
            SyncTrigger.AUTOMATIC,
            dayMillis(100),
        )

        assertEquals(setOf(AchievementIdentity(RA, "7")), plan.toFetch)
        assertEquals(listOf("14"), requested)
    }

    @Test
    fun `missing credentials pause the provider and fetch nothing`() = runTest {
        coEvery { remote.recentlyPlayed(any()) } returns RaSummaryResult.MissingCredentials

        val plan = strategy.plan(listOf(entry("7", lastDetailAt = null)), SyncTrigger.AUTOMATIC, dayMillis(100))

        assertEquals(UpdatePause.Credentials(RA), plan.pause)
        assertTrue(plan.toFetch.isEmpty())
        coVerify(exactly = 0) { remote.userProgress(any()) }
    }

    @Test
    fun `a network failure is a retryable provider failure`() = runTest {
        coEvery { remote.recentlyPlayed(any()) } returns
            RaSummaryResult.Failed("network error", offline = true, retryAfterMs = 60_000)

        val plan = strategy.plan(listOf(entry("7")), SyncTrigger.AUTOMATIC, dayMillis(100))

        assertTrue(plan.failed)
        assertEquals(UpdatePause.Offline, plan.pause)
        assertEquals(60_000L, plan.retryAfterMs)
    }

    @Test
    fun `a failed batch leaves its ids unchecked and the cohort unfinished`() = runTest {
        coEvery { remote.userProgress(any()) } returns RaSummaryResult.Failed("RetroAchievements returned 500")

        val plan = strategy.plan(ids(0, 3).map { entry(it) }, SyncTrigger.AUTOMATIC, dayMillis(100))

        assertTrue(plan.unchanged.isEmpty())
        assertTrue(plan.toFetch.isEmpty())
        coVerify(exactly = 0) { store.recordCohort(any(), any(), any()) }
    }

    @Test
    fun `ids missing from a partial response are left unchecked`() = runTest {
        val cohort = ids(0, 3)
        coEvery { remote.userProgress(any()) } answers {
            RaSummaryResult.Success(mapOf(cohort[0] to SAME))
        }

        val plan = strategy.plan(cohort.map { entry(it) }, SyncTrigger.AUTOMATIC, dayMillis(100))

        assertEquals(setOf(AchievementIdentity(RA, cohort[0])), plan.unchanged)
        assertTrue(plan.toFetch.isEmpty())
    }

    @Test
    fun `without a stored snapshot the cached set is the baseline`() = runTest {
        val matching = entry("7", snapshot = null).copy(storedEarned = 10, storedTotal = 40)
        val behind = entry("14", snapshot = null).copy(storedEarned = 9, storedTotal = 40)

        val plan = strategy.plan(listOf(matching, behind), SyncTrigger.AUTOMATIC, dayMillis(100))

        assertEquals(setOf(AchievementIdentity(RA, "7")), plan.unchanged)
        assertEquals(setOf(AchievementIdentity(RA, "14")), plan.toFetch)
    }

    @Test
    fun `no present ids means no requests at all`() = runTest {
        val plan = strategy.plan(emptyList(), SyncTrigger.MANUAL, dayMillis(100))

        assertEquals(ProviderCheckPlan(), plan)
        coVerify(exactly = 0) { remote.recentlyPlayed(any()) }
        coVerify(exactly = 0) { remote.userProgress(any()) }
    }

    private companion object {
        val RA = AchievementProvider.RETRO_ACHIEVEMENTS
        val SAME = RaProgressSnapshot(
            numAchieved = 10, numAchievedHardcore = 3, scoreAchieved = 100, scoreAchievedHardcore = 30,
            numPossible = 40, possibleScore = 400,
        )
        const val HOUR = 60L * 60 * 1_000
        const val DAY = 24 * HOUR
    }
}
