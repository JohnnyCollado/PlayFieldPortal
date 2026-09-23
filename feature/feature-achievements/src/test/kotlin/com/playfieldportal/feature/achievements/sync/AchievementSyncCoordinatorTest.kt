package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.database.entity.AchievementProviderSyncStateEntity
import com.playfieldportal.core.data.database.entity.AchievementTrackedIdentityEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.SyncedCoin
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Selective sync, Task 5: one coordinator owns every achievement refresh. It checks only the
 * currently present, confirmed identities the reconciler hands it, asks each provider's strategy
 * which of those need a full fetch, performs each fetch once even when triggers overlap, and never
 * lets a cancelled fetch write back after Clear all tracked achievements.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AchievementSyncCoordinatorTest {

    private val now = 10 * DAY
    private val reconciler = mockk<AchievementPresenceReconciler>()
    private val fetcher = mockk<AchievementDetailFetcher>()
    private val writer = mockk<AchievementSetWriter>()
    private val store = mockk<AchievementSyncStore>(relaxed = true)
    private val credentials = mockk<AchievementCredentialsProvider>(relaxed = true)
    private val reporter = mockk<AchievementUpdateReporter>(relaxed = true)
    private val raStrategy = mockk<ProviderCheckStrategy> { every { provider } returns RA }
    private val steamStrategy = mockk<ProviderCheckStrategy> { every { provider } returns STEAM }

    private val ra1 = AchievementIdentity(RA, "1")
    private val ra2 = AchievementIdentity(RA, "2")

    init {
        coEvery { credentials.autoUpdatesPaused() } returns false
        coEvery { store.providerState(any()) } returns null
        coEvery { store.identity(any()) } returns null
        coEvery { writer.write(any(), any(), any()) } returns true
        coEvery { steamStrategy.plan(any(), any(), any()) } returns ProviderCheckPlan()
    }

    private fun TestScope.coordinator() = AchievementSyncCoordinator(
        reconciler = reconciler,
        strategies = setOf(raStrategy, steamStrategy),
        fetcher = fetcher,
        writer = writer,
        store = store,
        credentials = credentials,
        reporter = reporter,
        localReturn = mockk(relaxed = true),
        clock = { now },
        scope = backgroundScope,
    )

    private fun entry(identity: AchievementIdentity, lastDetailAt: Long? = 1L) = TrackedEntry(
        identity = identity,
        title = "Game ${identity.providerGameId}",
        lastCheckedAt = lastDetailAt,
        lastDetailAt = lastDetailAt,
        retryAt = null,
        snapshot = null,
        storedEarned = null,
        storedTotal = null,
    )

    private fun present(vararg entries: TrackedEntry) {
        coEvery { reconciler.reconcile(any()) } returns PresenceResult(entries.toList(), newlyConfirmed = 0)
    }

    private fun success(id: String) = ProviderSyncResult.Success(
        id,
        listOf(SyncedCoin("x", "x", "", ShibaTier.BRONZE, 5.0, null, false, true, true, 1L)),
    )

    @Test
    fun `providers with no present identities make no requests`() = runTest {
        present(entry(ra1))
        coEvery { raStrategy.plan(any(), any(), any()) } returns ProviderCheckPlan(unchanged = setOf(ra1))

        coordinator().updateInstalled(SyncTrigger.MANUAL)

        coVerify(exactly = 0) { steamStrategy.plan(any(), any(), any()) }
        coVerify(exactly = 1) { raStrategy.plan(listOf(entry(ra1)), SyncTrigger.MANUAL, now) }
    }

    @Test
    fun `only the plan's changed identities are fetched and the counts add up`() = runTest {
        present(entry(ra1), entry(ra2), entry(AchievementIdentity(RA, "3")))
        coEvery { raStrategy.plan(any(), any(), any()) } returns ProviderCheckPlan(
            toFetch = setOf(ra1),
            unchanged = setOf(ra2),
            snapshots = mapOf(ra1 to "s1", ra2 to "s2"),
        )
        coEvery { fetcher.fetch(ra1, FetchReason.ROUTINE) } returns success("1")

        val summary = coordinator().updateInstalled(SyncTrigger.MANUAL)

        coVerify(exactly = 1) { fetcher.fetch(any(), any()) }
        coVerify { store.recordDetail(ra1, now, "s1") }
        coVerify { store.recordUnchanged(ra2, now, "s2") }
        assertEquals(3, summary.total)
        assertEquals(2, summary.checked)
        assertEquals(1, summary.updated)
        assertEquals(1, summary.unchanged)
        assertEquals(1, summary.skipped)
        assertEquals(0, summary.failed)
    }

    @Test
    fun `a newly matched identity is fetched as a new match, ahead of changed ones`() = runTest {
        present(entry(ra1), entry(ra2, lastDetailAt = null))
        coEvery { raStrategy.plan(any(), any(), any()) } returns ProviderCheckPlan(toFetch = setOf(ra1, ra2))
        val order = mutableListOf<Pair<AchievementIdentity, FetchReason>>()
        coEvery { fetcher.fetch(any(), any()) } answers {
            order += firstArg<AchievementIdentity>() to secondArg<FetchReason>()
            success(firstArg<AchievementIdentity>().providerGameId)
        }

        coordinator().updateInstalled(SyncTrigger.MANUAL)

        assertEquals(listOf(ra2 to FetchReason.NEW_MATCH, ra1 to FetchReason.ROUTINE), order)
    }

    @Test
    fun `a failed fetch keeps the cached set and is counted`() = runTest {
        present(entry(ra1))
        coEvery { raStrategy.plan(any(), any(), any()) } returns ProviderCheckPlan(toFetch = setOf(ra1))
        coEvery { fetcher.fetch(any(), any()) } returns ProviderSyncResult.Failed("network error")

        val summary = coordinator().updateInstalled(SyncTrigger.MANUAL)

        coVerify(exactly = 0) { writer.write(any(), any(), any()) }
        coVerify { store.recordFailure(ra1, now, any()) }
        assertEquals(1, summary.failed)
    }

    @Test
    fun `no achievement set is a check, not a failure`() = runTest {
        present(entry(ra1, lastDetailAt = null))
        coEvery { raStrategy.plan(any(), any(), any()) } returns ProviderCheckPlan(toFetch = setOf(ra1))
        coEvery { fetcher.fetch(any(), any()) } returns ProviderSyncResult.NotFound

        val summary = coordinator().updateInstalled(SyncTrigger.MANUAL)

        coVerify { store.recordNoSet(ra1, now) }
        assertEquals(0, summary.failed)
        assertEquals(1, summary.unchanged)
    }

    @Test
    fun `two simultaneous updates share one run and one fetch per identity`() = runTest {
        present(entry(ra1))
        coEvery { raStrategy.plan(any(), any(), any()) } returns ProviderCheckPlan(toFetch = setOf(ra1))
        val gate = CompletableDeferred<ProviderSyncResult>()
        coEvery { fetcher.fetch(any(), any()) } coAnswers { gate.await() }
        val coordinator = coordinator()

        val first = async { coordinator.updateInstalled(SyncTrigger.MANUAL) }
        val second = async { coordinator.updateInstalled(SyncTrigger.AUTOMATIC) }
        runCurrent()
        gate.complete(success("1"))

        assertEquals(first.await(), second.await())
        coVerify(exactly = 1) { fetcher.fetch(any(), any()) }
        coVerify(exactly = 1) { reconciler.reconcile(any()) }
    }

    @Test
    fun `an explicit refresh during a batch joins the in-flight fetch`() = runTest {
        present(entry(ra1))
        coEvery { raStrategy.plan(any(), any(), any()) } returns ProviderCheckPlan(toFetch = setOf(ra1))
        coEvery { store.identity(ra1) } returns identityRow(ra1, present = true)
        val gate = CompletableDeferred<ProviderSyncResult>()
        coEvery { fetcher.fetch(any(), any()) } coAnswers { gate.await() }
        val coordinator = coordinator()

        val batch = async { coordinator.updateInstalled(SyncTrigger.MANUAL) }
        runCurrent()
        val refresh = async { coordinator.refreshIdentity(ra1, "Game 1") }
        runCurrent()
        gate.complete(success("1"))

        assertTrue(refresh.await() is ProviderSyncResult.Success)
        batch.await()
        coVerify(exactly = 1) { fetcher.fetch(any(), any()) }
        coVerify(exactly = 1) { writer.write(any(), any(), any()) }
    }

    @Test
    fun `an automatic run skips a provider checked in the last 24 hours`() = runTest {
        present(entry(ra1))
        coEvery { store.providerState(RA) } returns
            AchievementProviderSyncStateEntity(provider = "RETRO_ACHIEVEMENTS", lastCheckedAt = now - HOUR)

        val summary = coordinator().updateInstalled(SyncTrigger.AUTOMATIC)

        coVerify(exactly = 0) { raStrategy.plan(any(), any(), any()) }
        assertEquals(1, summary.skipped)
    }

    @Test
    fun `an automatic run waits out a provider's retry time`() = runTest {
        present(entry(ra1))
        coEvery { store.providerState(RA) } returns AchievementProviderSyncStateEntity(
            provider = "RETRO_ACHIEVEMENTS", lastCheckedAt = now - 2 * DAY, retryAt = now + HOUR,
        )

        coordinator().updateInstalled(SyncTrigger.AUTOMATIC)

        coVerify(exactly = 0) { raStrategy.plan(any(), any(), any()) }
    }

    @Test
    fun `a provider-level failure schedules a backoff and fetches nothing`() = runTest {
        present(entry(ra1))
        coEvery { raStrategy.plan(any(), any(), any()) } returns
            ProviderCheckPlan(failed = true, pause = UpdatePause.Offline)

        val summary = coordinator().updateInstalled(SyncTrigger.MANUAL)

        coVerify(exactly = 0) { fetcher.fetch(any(), any()) }
        coVerify { store.recordProviderFailure(RA, now, match { it > now }, UpdatePause.Offline) }
        assertEquals(setOf<UpdatePause>(UpdatePause.Offline), summary.pauses)
    }

    @Test
    fun `nothing runs automatically while updates are paused after a clear`() = runTest {
        coEvery { credentials.autoUpdatesPaused() } returns true

        val summary = coordinator().updateInstalled(SyncTrigger.AUTOMATIC)

        coVerify(exactly = 0) { reconciler.reconcile(any()) }
        assertTrue(summary.blocked)
    }

    @Test
    fun `a manual update resumes the schedule and confirms present matches`() = runTest {
        coEvery { credentials.autoUpdatesPaused() } returns true
        present()

        coordinator().updateInstalled(SyncTrigger.MANUAL)

        coVerify { credentials.setAutoUpdatesPaused(false) }
        coVerify { reconciler.reconcile(confirmNew = true) }
    }

    @Test
    fun `a scheduled repeat of the same pause is not reported again`() = runTest {
        present(entry(ra1))
        coEvery { store.providerState(RA) } returns AchievementProviderSyncStateEntity(
            provider = "RETRO_ACHIEVEMENTS", lastCheckedAt = now - 2 * DAY,
            pausedReason = UpdatePause.Credentials(RA).code,
        )
        coEvery { raStrategy.plan(any(), any(), any()) } returns
            ProviderCheckPlan(pause = UpdatePause.Credentials(RA))

        coordinator().updateInstalled(SyncTrigger.AUTOMATIC)

        coVerify { reporter.finished(SyncTrigger.AUTOMATIC, any(), newPauses = emptySet()) }
    }

    @Test
    fun `clear during an in-flight fetch cancels it and nothing is written late`() = runTest {
        present(entry(ra1))
        coEvery { raStrategy.plan(any(), any(), any()) } returns ProviderCheckPlan(toFetch = setOf(ra1))
        val gate = CompletableDeferred<ProviderSyncResult>()
        coEvery { fetcher.fetch(any(), any()) } coAnswers { gate.await() }
        val coordinator = coordinator()

        val batch = async { runCatching { coordinator.updateInstalled(SyncTrigger.MANUAL) } }
        runCurrent()
        val cleared = coordinator.clearAll()
        gate.complete(success("1"))
        advanceUntilIdle()
        batch.await()

        assertTrue(cleared)
        coVerify { credentials.setAutoUpdatesPaused(true) }
        coVerify { store.clearAll() }
        coVerify(exactly = 0) { writer.write(any(), any(), any()) }
        coVerify { reporter.cleared(true) }
    }

    @Test
    fun `nothing new starts while a clear is running`() = runTest {
        present(entry(ra1))
        val clearGate = CompletableDeferred<Unit>()
        coEvery { store.clearAll() } coAnswers { clearGate.await() }
        val coordinator = coordinator()

        val clearing = async { coordinator.clearAll() }
        runCurrent()
        val summary = coordinator.updateInstalled(SyncTrigger.MANUAL)
        clearGate.complete(Unit)
        clearing.await()

        assertTrue(summary.blocked)
        coVerify(exactly = 0) { raStrategy.plan(any(), any(), any()) }
    }

    @Test
    fun `a failed clear reports failure and keeps automatic updates as they were`() = runTest {
        coEvery { store.clearAll() } throws IllegalStateException("disk full")

        val cleared = coordinator().clearAll()

        assertFalse(cleared)
        coVerify { credentials.setAutoUpdatesPaused(false) }
        coVerify { reporter.cleared(false) }
    }

    @Test
    fun `stale-on-open skips detail checked within 24 hours`() = runTest {
        coEvery { store.identity(ra1) } returns identityRow(ra1, present = true, lastDetailAt = now - HOUR)

        assertNull(coordinator().refreshIfStale(ra1, "Game 1"))
        coVerify(exactly = 0) { fetcher.fetch(any(), any()) }
    }

    @Test
    fun `stale-on-open refreshes stale detail once`() = runTest {
        coEvery { store.identity(ra1) } returns identityRow(ra1, present = true, lastDetailAt = now - 2 * DAY)
        coEvery { fetcher.fetch(ra1, FetchReason.STALE_ON_OPEN) } returns success("1")

        val result = coordinator().refreshIfStale(ra1, "Game 1")

        assertTrue(result is ProviderSyncResult.Success)
        coVerify(exactly = 1) { fetcher.fetch(ra1, FetchReason.STALE_ON_OPEN) }
    }

    @Test
    fun `stale-on-open never fetches a game that is not installed`() = runTest {
        coEvery { store.identity(ra1) } returns identityRow(ra1, present = false, lastDetailAt = now - 30 * DAY)

        assertNull(coordinator().refreshIfStale(ra1, "Game 1"))
        coVerify(exactly = 0) { fetcher.fetch(any(), any()) }
    }

    @Test
    fun `an explicit refresh of a removed game is refused`() = runTest {
        coEvery { store.identity(ra1) } returns identityRow(ra1, present = false)

        val result = coordinator().refreshIdentity(ra1, "Game 1")

        assertEquals(ProviderSyncResult.NotLinked, result)
        coVerify(exactly = 0) { fetcher.fetch(any(), any()) }
    }

    @Test
    fun `an automatic check is due only when a provider is past its 24 hour window`() = runTest {
        coEvery { store.providerState(any()) } returns AchievementProviderSyncStateEntity(
            provider = "X", lastCheckedAt = now - HOUR,
        )
        assertFalse(coordinator().isAutomaticCheckDue())

        coEvery { store.providerState(STEAM) } returns AchievementProviderSyncStateEntity(
            provider = "STEAM", lastCheckedAt = now - 25 * HOUR,
        )
        assertTrue(coordinator().isAutomaticCheckDue())

        coEvery { credentials.autoUpdatesPaused() } returns true
        assertFalse(coordinator().isAutomaticCheckDue())
    }

    private fun identityRow(
        identity: AchievementIdentity,
        present: Boolean,
        lastDetailAt: Long? = 1L,
    ) = AchievementTrackedIdentityEntity(
        provider = identity.provider.name,
        providerGameId = identity.providerGameId,
        title = "Game",
        firstMatchedAt = 0L,
        lastMatchedAt = 0L,
        isPresent = present,
        lastDetailAt = lastDetailAt,
    )

    private companion object {
        val RA = AchievementProvider.RETRO_ACHIEVEMENTS
        val STEAM = AchievementProvider.STEAM
        const val HOUR = 60L * 60 * 1_000
        const val DAY = 24 * HOUR
    }
}
