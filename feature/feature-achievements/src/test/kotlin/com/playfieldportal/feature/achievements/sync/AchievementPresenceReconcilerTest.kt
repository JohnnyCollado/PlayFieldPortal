package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.data.database.dao.AchievementTrackingDao
import com.playfieldportal.core.data.database.dao.LinkPresenceRow
import com.playfieldportal.core.data.database.entity.AchievementTrackedIdentityEntity
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamDiscovery
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamGame
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Selective sync, Task 3: presence is reconciled from live links (whose game is not flagged
 * missing) plus discovered Local Steam folders. A present identity is confirmed into the ledger; an
 * absent one is only marked not-present — its history and cached coins stay. Nothing is inferred
 * from a bare account set, and while updates are paused after a clear no new identity is confirmed.
 */
class AchievementPresenceReconcilerTest {

    private val dao = mockk<AchievementTrackingDao>(relaxed = true)
    private val discovery = mockk<LocalSteamDiscovery>()
    private val reconciler = AchievementPresenceReconciler(dao, discovery, clock = { NOW })

    init {
        coEvery { discovery.scan() } returns emptyList()
        coEvery { dao.getAllIdentities() } returns emptyList()
        coEvery { dao.presentEntries() } returns emptyList()
    }

    private fun link(gameId: Long, provider: String, id: String, missing: Boolean = false, title: String = "Game $gameId") =
        LinkPresenceRow(gameId = gameId, provider = provider, providerGameId = id, title = title, isMissing = missing)

    private fun ledger(provider: String, id: String, present: Boolean) = AchievementTrackedIdentityEntity(
        provider = provider, providerGameId = id, title = "Old", firstMatchedAt = 1L, lastMatchedAt = 1L,
        isPresent = present,
    )

    private fun folder(appId: String, name: String) =
        LocalSteamGame(folderName = name, folderDocId = "doc$appId", appId = appId, achievementsUri = null)

    @Test
    fun `a present linked game is confirmed, a missing one is not`() = runTest {
        coEvery { dao.linksWithPresence() } returns listOf(
            link(1, "RETRO_ACHIEVEMENTS", "319"),
            link(2, "RETRO_ACHIEVEMENTS", "412", missing = true),
        )

        val result = reconciler.reconcile(confirmNew = true)

        coVerify { dao.confirm("RETRO_ACHIEVEMENTS", "319", "Game 1", NOW) }
        coVerify(exactly = 0) { dao.confirm("RETRO_ACHIEVEMENTS", "412", any(), any()) }
        assertEquals(1, result.newlyConfirmed)
    }

    @Test
    fun `two local copies of one provider game make one identity`() = runTest {
        coEvery { dao.linksWithPresence() } returns listOf(
            link(1, "RETRO_ACHIEVEMENTS", "319"),
            link(3, "RETRO_ACHIEVEMENTS", "319"),
        )

        val result = reconciler.reconcile(confirmNew = true)

        coVerify(exactly = 1) { dao.confirm("RETRO_ACHIEVEMENTS", "319", any(), any()) }
        assertEquals(1, result.newlyConfirmed)
    }

    @Test
    fun `a removed game is marked not present and keeps its ledger row`() = runTest {
        coEvery { dao.linksWithPresence() } returns emptyList()
        coEvery { dao.getAllIdentities() } returns listOf(ledger("STEAM", "220", present = true))

        reconciler.reconcile(confirmNew = true)

        coVerify { dao.markAbsent("STEAM", "220") }
        coVerify(exactly = 0) { dao.deleteIdentity(any(), any()) }
    }

    @Test
    fun `a reinstalled game reconnects to its existing identity`() = runTest {
        coEvery { dao.linksWithPresence() } returns listOf(link(9, "STEAM", "220"))
        coEvery { dao.getAllIdentities() } returns listOf(ledger("STEAM", "220", present = false))

        val result = reconciler.reconcile(confirmNew = true)

        coVerify { dao.markPresent("STEAM", "220", NOW) }
        coVerify(exactly = 0) { dao.confirm(any(), any(), any(), any()) }
        assertEquals(0, result.newlyConfirmed)
    }

    @Test
    fun `a discovered local steam folder is present, with or without a library link`() = runTest {
        coEvery { dao.linksWithPresence() } returns listOf(link(4, "LOCAL_STEAM", "1000"))
        coEvery { discovery.scan() } returns listOf(folder("1000", "Linked"), folder("2000", "Folder Only"))

        reconciler.reconcile(confirmNew = true)

        coVerify { dao.confirm("LOCAL_STEAM", "1000", "Game 4", NOW) }
        coVerify { dao.confirm("LOCAL_STEAM", "2000", "Folder Only", NOW) }
    }

    @Test
    fun `a vanished local steam folder becomes history rather than being pruned`() = runTest {
        coEvery { dao.linksWithPresence() } returns listOf(link(4, "LOCAL_STEAM", "1000"))
        coEvery { dao.getAllIdentities() } returns listOf(
            ledger("LOCAL_STEAM", "1000", present = true),
            ledger("LOCAL_STEAM", "2000", present = true),
        )
        coEvery { discovery.scan() } returns emptyList()

        reconciler.reconcile(confirmNew = true)

        coVerify { dao.markAbsent("LOCAL_STEAM", "1000") }
        coVerify { dao.markAbsent("LOCAL_STEAM", "2000") }
        coVerify(exactly = 0) { dao.deleteIdentity(any(), any()) }
    }

    @Test
    fun `a failed folder scan leaves local steam presence as it was`() = runTest {
        coEvery { dao.linksWithPresence() } returns emptyList()
        coEvery { dao.getAllIdentities() } returns listOf(ledger("LOCAL_STEAM", "1000", present = true))
        coEvery { discovery.scan() } throws SecurityException("grant revoked")

        reconciler.reconcile(confirmNew = true)

        coVerify(exactly = 0) { dao.markAbsent("LOCAL_STEAM", any()) }
    }

    @Test
    fun `while paused after a clear, no new identity enters the ledger`() = runTest {
        coEvery { dao.linksWithPresence() } returns listOf(link(1, "RETRO_ACHIEVEMENTS", "319"))

        val result = reconciler.reconcile(confirmNew = false)

        coVerify(exactly = 0) { dao.confirm(any(), any(), any(), any()) }
        assertEquals(0, result.newlyConfirmed)
    }

    @Test
    fun `an uninstalled account library creates no queue`() = runTest {
        // Only the account knows these games; nothing local links to them.
        coEvery { dao.linksWithPresence() } returns emptyList()

        val result = reconciler.reconcile(confirmNew = true)

        coVerify(exactly = 0) { dao.confirm(any(), any(), any(), any()) }
        assertEquals(emptyList(), result.entries)
    }

    private companion object {
        const val NOW = 5_000L
    }
}
