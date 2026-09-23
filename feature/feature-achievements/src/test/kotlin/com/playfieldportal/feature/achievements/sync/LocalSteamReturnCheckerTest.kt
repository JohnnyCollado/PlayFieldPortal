package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.data.database.entity.AchievementTrackedIdentityEntity
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.SyncedCoin
import com.playfieldportal.feature.achievements.provider.localsteam.EmuEarnedAchievement
import com.playfieldportal.feature.achievements.provider.localsteam.LocalEarnedRead
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamSource
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Selective sync, Task 5/7: returning from a LOCAL_STEAM game launched through PFP reads that one
 * game's local progress file. An unchanged fingerprint writes nothing; a changed one updates the
 * coins from cached schema and rarity with no Steam Web API request; a missing or unreadable file
 * is "unknown", never proof that earned coins were lost.
 */
class LocalSteamReturnCheckerTest {

    private val source = mockk<LocalSteamSource>()
    private val store = mockk<AchievementSyncStore>(relaxed = true)
    private val writer = mockk<AchievementSetWriter>()
    private val checker = LocalSteamReturnChecker(source, store, writer, clock = { NOW })

    private val identity = AchievementIdentity(AchievementProvider.LOCAL_STEAM, APP)
    private val earned = listOf(EmuEarnedAchievement("WIN", earned = true, earnedAtEpochSeconds = 10))
    private val coins = listOf(SyncedCoin("WIN", "Win", "", ShibaTier.BRONZE, 50.0, null, false, true, true, 10_000L))

    init {
        coEvery { store.localSteamIdentityForGame(GAME) } returns ledger(snapshot = "old")
        coEvery { writer.write(any(), any(), any()) } returns true
    }

    private fun ledger(snapshot: String?) = AchievementTrackedIdentityEntity(
        provider = "LOCAL_STEAM", providerGameId = APP, title = "Resonance of Fate",
        firstMatchedAt = 1L, lastMatchedAt = 1L, isPresent = true, summarySnapshot = snapshot,
    )

    @Test
    fun `unchanged local progress writes nothing and asks Steam nothing`() = runTest {
        coEvery { source.readEarned(APP) } returns LocalEarnedRead.Read(earned, fingerprint = "old")

        assertEquals(LocalReturnOutcome.UNCHANGED, checker.check(GAME))

        coVerify(exactly = 0) { writer.write(any(), any(), any()) }
        coVerify(exactly = 0) { source.mapFromCache(any(), any()) }
        coVerify(exactly = 0) { source.fetch(any()) }
    }

    @Test
    fun `changed local progress updates from cached metadata only`() = runTest {
        coEvery { source.readEarned(APP) } returns LocalEarnedRead.Read(earned, fingerprint = "new")
        coEvery { source.mapFromCache(APP, any()) } returns ProviderSyncResult.Success(APP, coins)

        assertEquals(LocalReturnOutcome.UPDATED, checker.check(GAME))

        coVerify { writer.write(identity, "Resonance of Fate", coins) }
        coVerify { store.recordDetail(identity, NOW, "new") }
        coVerify(exactly = 0) { source.fetch(any()) }
    }

    @Test
    fun `without cached metadata the change falls back to one full fetch`() = runTest {
        coEvery { source.readEarned(APP) } returns LocalEarnedRead.Read(earned, fingerprint = "new")
        coEvery { source.mapFromCache(APP, any()) } returns null
        coEvery { source.fetch(APP) } returns ProviderSyncResult.Success(APP, coins)

        assertEquals(LocalReturnOutcome.UPDATED, checker.check(GAME))

        coVerify(exactly = 1) { source.fetch(APP) }
    }

    @Test
    fun `a missing progress file is unknown and keeps the earned coins`() = runTest {
        coEvery { source.readEarned(APP) } returns LocalEarnedRead.Unknown

        assertEquals(LocalReturnOutcome.UNKNOWN, checker.check(GAME))

        coVerify(exactly = 0) { writer.write(any(), any(), any()) }
        coVerify(exactly = 0) { store.recordDetail(any(), any(), any()) }
    }

    @Test
    fun `a game with no present local steam identity is not checked`() = runTest {
        coEvery { store.localSteamIdentityForGame(GAME) } returns null

        assertEquals(LocalReturnOutcome.NOT_LOCAL_STEAM, checker.check(GAME))

        coVerify(exactly = 0) { source.readEarned(any()) }
    }

    private companion object {
        const val GAME = 42L
        const val APP = "396960"
        const val NOW = 9_000L
    }
}
