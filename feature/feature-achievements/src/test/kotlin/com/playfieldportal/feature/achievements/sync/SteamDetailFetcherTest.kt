package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.data.database.dao.AccountAchievementDao
import com.playfieldportal.core.data.database.entity.AccountAchievementEntity
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.provider.steam.SteamMetadata
import com.playfieldportal.feature.achievements.provider.steam.SteamMetadataStore
import com.playfieldportal.feature.achievements.provider.steam.SteamPlayerAchievement
import com.playfieldportal.feature.achievements.provider.steam.SteamPlayerResult
import com.playfieldportal.feature.achievements.provider.steam.SteamRemoteDataSource
import com.playfieldportal.feature.achievements.provider.steam.SteamSchemaAchievement
import com.playfieldportal.feature.achievements.provider.steam.SteamSchemaResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Selective sync, Task 7: Steam schema and global rarity are cached apart from the player's
 * unlocks. A routine changed-playtime refresh costs one GetPlayerAchievements call when metadata
 * is cached; schema and rarity are requested on first fetch, on an explicit refresh, or when the
 * game being viewed has metadata older than 30 days — never on a routine pass, so metadata expiry
 * can't turn into a request for every installed title at once.
 */
class SteamDetailFetcherTest {

    private val steam = mockk<SteamRemoteDataSource>()
    private val store = mockk<SteamMetadataStore>(relaxed = true)
    private val coinDao = mockk<AccountAchievementDao>()
    private val fetcher = SteamDetailFetcher(steam, store, coinDao, clock = { NOW })

    private val schema = listOf(
        SteamSchemaAchievement(name = "WIN", displayName = "Win", description = "Win once"),
        SteamSchemaAchievement(name = "SECRET", displayName = "Secret", hidden = 1),
    )

    init {
        coEvery { steam.fetchSchema(APP) } returns SteamSchemaResult.Success(schema)
        coEvery { steam.fetchGlobalRarity(APP) } returns mapOf("WIN" to 50.0, "SECRET" to 3.0)
        coEvery { steam.fetchPlayerAchievements(APP) } returns SteamPlayerResult.Success(
            mapOf("WIN" to SteamPlayerAchievement("WIN", achieved = 1, unlocktime = 100)),
        )
        coEvery { steam.enrichHiddenDescriptions(APP, any()) } answers { secondArg() }
        coEvery { coinDao.getForSet(any(), any()) } returns emptyList()
        coEvery { store.get(APP) } returns null
    }

    private fun cached(ageMs: Long) {
        coEvery { store.get(APP) } returns SteamMetadata(schema, mapOf("WIN" to 50.0, "SECRET" to 3.0), fetchedAt = NOW - ageMs)
    }

    @Test
    fun `a routine refresh with cached metadata makes one player call`() = runTest {
        cached(ageMs = DAY)

        val result = assertIs<ProviderSyncResult.Success>(fetcher.fetch(APP, FetchReason.ROUTINE))

        assertEquals(2, result.coins.size)
        coVerify(exactly = 0) { steam.fetchSchema(any()) }
        coVerify(exactly = 0) { steam.fetchGlobalRarity(any()) }
        coVerify(exactly = 1) { steam.fetchPlayerAchievements(APP) }
    }

    @Test
    fun `a first fetch requests schema and rarity once and caches them`() = runTest {
        fetcher.fetch(APP, FetchReason.NEW_MATCH)

        coVerify(exactly = 1) { steam.fetchSchema(APP) }
        coVerify(exactly = 1) { steam.fetchGlobalRarity(APP) }
        coVerify { store.put(APP, match { it.schema == schema && it.fetchedAt == NOW }) }
    }

    @Test
    fun `an explicit refresh renews fresh metadata`() = runTest {
        cached(ageMs = DAY)

        fetcher.fetch(APP, FetchReason.EXPLICIT)

        coVerify(exactly = 1) { steam.fetchSchema(APP) }
    }

    @Test
    fun `opening a game renews metadata only once it is older than 30 days`() = runTest {
        cached(ageMs = 5 * DAY)
        fetcher.fetch(APP, FetchReason.STALE_ON_OPEN)
        coVerify(exactly = 0) { steam.fetchSchema(any()) }

        cached(ageMs = 31 * DAY)
        fetcher.fetch(APP, FetchReason.STALE_ON_OPEN)
        coVerify(exactly = 1) { steam.fetchSchema(APP) }
    }

    @Test
    fun `a routine refresh never renews old metadata`() = runTest {
        cached(ageMs = 90 * DAY)

        fetcher.fetch(APP, FetchReason.ROUTINE)

        coVerify(exactly = 0) { steam.fetchSchema(any()) }
        coVerify(exactly = 1) { steam.fetchPlayerAchievements(APP) }
    }

    @Test
    fun `private game details are reported, not read as zero unlocks`() = runTest {
        cached(ageMs = DAY)
        coEvery { steam.fetchPlayerAchievements(APP) } returns SteamPlayerResult.ProfileNotPublic

        assertEquals(ProviderSyncResult.ProfileNotPublic, fetcher.fetch(APP, FetchReason.ROUTINE))
    }

    @Test
    fun `a game with no schema is a real no-achievements answer`() = runTest {
        coEvery { steam.fetchSchema(APP) } returns SteamSchemaResult.NotFound

        assertEquals(ProviderSyncResult.NotFound, fetcher.fetch(APP, FetchReason.NEW_MATCH))
        coVerify(exactly = 0) { steam.fetchPlayerAchievements(any()) }
    }

    @Test
    fun `a failed schema request is a failure, never an empty set`() = runTest {
        coEvery { steam.fetchSchema(APP) } returns SteamSchemaResult.Failed("schema request failed")

        assertIs<ProviderSyncResult.Failed>(fetcher.fetch(APP, FetchReason.NEW_MATCH))
    }

    @Test
    fun `a stored hidden description is kept instead of scraping the community page again`() = runTest {
        cached(ageMs = DAY)
        coEvery { steam.fetchPlayerAchievements(APP) } returns SteamPlayerResult.Success(
            mapOf("SECRET" to SteamPlayerAchievement("SECRET", achieved = 1, unlocktime = 100)),
        )
        coEvery { coinDao.getForSet("STEAM", APP) } returns listOf(
            AccountAchievementEntity(
                provider = "STEAM", providerGameId = APP, providerAchievementId = "SECRET",
                title = "Secret", description = "Found the room", tier = "GOLD", globalRarity = 3.0,
                isHidden = true, isEarned = true,
            ),
        )

        val result = assertIs<ProviderSyncResult.Success>(fetcher.fetch(APP, FetchReason.ROUTINE))

        assertEquals("Found the room", result.coins.single { it.providerAchievementId == "SECRET" }.description)
        coVerify(exactly = 0) { steam.enrichHiddenDescriptions(any(), any()) }
    }

    private companion object {
        const val APP = "440"
        const val DAY = 24L * 60 * 60 * 1_000
        const val NOW = 400 * DAY
    }
}
