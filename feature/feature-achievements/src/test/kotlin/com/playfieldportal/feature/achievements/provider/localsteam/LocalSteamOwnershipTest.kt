package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.core.data.database.dao.ProviderGameLinkDao
import com.playfieldportal.core.data.database.dao.SteamOwnedGamesDao
import com.playfieldportal.core.data.database.entity.ProviderGameLinkEntity
import com.playfieldportal.core.domain.achievement.LocalCopyOwnership
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The classification rules of docs/local-steam-achievements-plan.md section 5: an empty owned
 * cache means UNKNOWN — `isOwned == false` alone never becomes NOT_IN_LIBRARY.
 */
class LocalSteamOwnershipTest {

    private val ownedDao = mockk<SteamOwnedGamesDao>()
    private val linkDao = mockk<ProviderGameLinkDao>(relaxed = true)
    private val ownership = LocalSteamOwnership(ownedDao, linkDao)

    @Test
    fun `an empty owned cache is UNKNOWN, never NOT_IN_LIBRARY`() = runTest {
        coEvery { ownedDao.ownedCount() } returns 0

        assertNull(ownership.derive("2753970"))
        assertNull(ownership.classify(7L, "2753970"))
        coVerify { linkDao.setOwnership(7L, "LOCAL_STEAM", null) }
    }

    @Test
    fun `a populated cache classifies owned and not-in-library`() = runTest {
        coEvery { ownedDao.ownedCount() } returns 12
        coEvery { ownedDao.isOwned("359870") } returns true
        coEvery { ownedDao.isOwned("2753970") } returns false

        assertEquals(LocalCopyOwnership.OWNED, ownership.derive("359870"))
        assertEquals(LocalCopyOwnership.NOT_IN_LIBRARY, ownership.derive("2753970"))
    }

    @Test
    fun `refreshAll re-derives every LOCAL_STEAM link`() = runTest {
        coEvery { ownedDao.ownedCount() } returns 12
        coEvery { ownedDao.isOwned("359870") } returns true
        coEvery { ownedDao.isOwned("2753970") } returns false
        coEvery { linkDao.getByProvider("LOCAL_STEAM") } returns listOf(
            link(gameId = 1L, appId = "359870"),
            link(gameId = 2L, appId = "2753970"),
        )

        ownership.refreshAll()

        coVerify { linkDao.setOwnership(1L, "LOCAL_STEAM", "OWNED") }
        coVerify { linkDao.setOwnership(2L, "LOCAL_STEAM", "NOT_IN_LIBRARY") }
    }

    private fun link(gameId: Long, appId: String) = ProviderGameLinkEntity(
        gameId = gameId,
        provider = "LOCAL_STEAM",
        providerGameId = appId,
        source = "MANUAL",
        resolvedAt = 0L,
    )
}
