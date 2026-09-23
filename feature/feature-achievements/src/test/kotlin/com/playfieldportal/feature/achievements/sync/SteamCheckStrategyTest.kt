package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.data.database.dao.SteamOwnedGamesDao
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamOwnership
import com.playfieldportal.feature.achievements.provider.steam.SteamOwnedEntry
import com.playfieldportal.feature.achievements.provider.steam.SteamOwnedGamesResult
import com.playfieldportal.feature.achievements.provider.steam.SteamRemoteDataSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Selective sync, Task 7: Steam is checked playtime-first. One GetOwnedGames call per check; only
 * present matched app ids are compared against the playtime saved at their last detail fetch, and
 * only a new match or a changed playtime gets a player-achievement fetch. A private or failed
 * owned-games answer is never read as an empty library and never triggers a per-game sweep. The
 * owned-games cache is still refreshed for local-copy ownership classification.
 */
class SteamCheckStrategyTest {

    private val steam = mockk<SteamRemoteDataSource>()
    private val ownedDao = mockk<SteamOwnedGamesDao>(relaxed = true)
    private val ownership = mockk<LocalSteamOwnership>(relaxed = true)
    private val strategy = SteamCheckStrategy(steam, ownedDao, ownership)

    init {
        coEvery { ownedDao.syncedPlaytime(any()) } returns null
    }

    private fun owned(vararg games: Pair<String, Long>) {
        coEvery { steam.ownedGames() } returns SteamOwnedGamesResult.Success(
            games.map { (id, minutes) -> SteamOwnedEntry(id, "Game $id", minutes) },
        )
    }

    private fun entry(appId: String, playtime: Long?, lastDetailAt: Long? = 1L) = TrackedEntry(
        identity = AchievementIdentity(STEAM, appId),
        title = "Game $appId",
        lastCheckedAt = 1L,
        lastDetailAt = lastDetailAt,
        retryAt = null,
        snapshot = playtime?.let { SteamCheckStrategy.snapshotOf(it) },
        storedEarned = null,
        storedTotal = null,
    )

    @Test
    fun `an unchanged installed library needs no per-game fetch`() = runTest {
        // 1,000 owned games, 3 installed: only the 3 are compared.
        coEvery { steam.ownedGames() } returns SteamOwnedGamesResult.Success(
            (1..1_000).map { SteamOwnedEntry(it.toString(), "Game $it", 60) },
        )

        val plan = strategy.plan(
            listOf(entry("1", 60), entry("2", 60), entry("3", 60)),
            SyncTrigger.AUTOMATIC,
            NOW,
        )

        assertTrue(plan.toFetch.isEmpty())
        assertEquals(3, plan.unchanged.size)
        coVerify(exactly = 1) { steam.ownedGames() }
    }

    @Test
    fun `a changed playtime refreshes only that app`() = runTest {
        owned("440" to 120, "620" to 30)

        val plan = strategy.plan(listOf(entry("440", 60), entry("620", 30)), SyncTrigger.AUTOMATIC, NOW)

        assertEquals(setOf(AchievementIdentity(STEAM, "440")), plan.toFetch)
        assertEquals(setOf(AchievementIdentity(STEAM, "620")), plan.unchanged)
        assertEquals(SteamCheckStrategy.snapshotOf(120), plan.snapshots[AchievementIdentity(STEAM, "440")])
    }

    @Test
    fun `a newly recognized game is fetched once`() = runTest {
        owned("440" to 0)

        val plan = strategy.plan(listOf(entry("440", null, lastDetailAt = null)), SyncTrigger.AUTOMATIC, NOW)

        assertEquals(setOf(AchievementIdentity(STEAM, "440")), plan.toFetch)
    }

    @Test
    fun `the old import bookmark is the baseline when no snapshot exists yet`() = runTest {
        owned("440" to 90, "620" to 45)
        coEvery { ownedDao.syncedPlaytime("440") } returns 90
        coEvery { ownedDao.syncedPlaytime("620") } returns 10

        val plan = strategy.plan(listOf(entry("440", null), entry("620", null)), SyncTrigger.AUTOMATIC, NOW)

        assertEquals(setOf(AchievementIdentity(STEAM, "440")), plan.unchanged)
        assertEquals(setOf(AchievementIdentity(STEAM, "620")), plan.toFetch)
    }

    @Test
    fun `private game details pause steam and never sweep the installed games`() = runTest {
        coEvery { steam.ownedGames() } returns SteamOwnedGamesResult.ProfileNotPublic

        val plan = strategy.plan(listOf(entry("440", 60), entry("620", null, lastDetailAt = null)), SyncTrigger.AUTOMATIC, NOW)

        assertEquals(UpdatePause.SteamPrivate, plan.pause)
        assertTrue(plan.toFetch.isEmpty())
        coVerify(exactly = 0) { ownedDao.replaceOwned(any()) }
    }

    @Test
    fun `missing credentials pause steam`() = runTest {
        coEvery { steam.ownedGames() } returns SteamOwnedGamesResult.MissingCredentials

        val plan = strategy.plan(listOf(entry("440", 60)), SyncTrigger.AUTOMATIC, NOW)

        assertEquals(UpdatePause.Credentials(STEAM), plan.pause)
        assertTrue(plan.toFetch.isEmpty())
    }

    @Test
    fun `an owned-games failure is retryable and keeps the cache`() = runTest {
        coEvery { steam.ownedGames() } returns SteamOwnedGamesResult.Failed("network error")

        val plan = strategy.plan(listOf(entry("440", 60)), SyncTrigger.AUTOMATIC, NOW)

        assertTrue(plan.failed)
        assertTrue(plan.toFetch.isEmpty())
        coVerify(exactly = 0) { ownedDao.replaceOwned(any()) }
    }

    @Test
    fun `a game missing from the owned list is left unchecked, not refetched`() = runTest {
        owned("620" to 30)

        val plan = strategy.plan(listOf(entry("440", 60)), SyncTrigger.AUTOMATIC, NOW)

        assertTrue(plan.toFetch.isEmpty())
        assertFalse(AchievementIdentity(STEAM, "440") in plan.unchanged)
    }

    @Test
    fun `the owned cache and local-copy ownership are refreshed from the one call`() = runTest {
        owned("440" to 60)

        strategy.plan(listOf(entry("440", 60)), SyncTrigger.AUTOMATIC, NOW)

        coVerify { ownedDao.replaceOwned(match { it.single().appid == "440" }) }
        coVerify { ownership.refreshAll() }
    }

    @Test
    fun `no present steam games means no owned-games call`() = runTest {
        val plan = strategy.plan(emptyList(), SyncTrigger.MANUAL, NOW)

        assertEquals(ProviderCheckPlan(), plan)
        coVerify(exactly = 0) { steam.ownedGames() }
    }

    private companion object {
        val STEAM = AchievementProvider.STEAM
        const val NOW = 1_000_000L
    }
}
