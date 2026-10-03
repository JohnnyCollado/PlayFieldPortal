package com.playfieldportal.feature.achievements.provider.localsteam

import android.net.Uri
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.data.database.dao.AccountAchievementDao
import com.playfieldportal.core.data.database.entity.AccountAchievementEntity
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.SyncedCoin
import com.playfieldportal.feature.achievements.provider.steam.SteamGame
import com.playfieldportal.feature.achievements.provider.steam.SteamGameStats
import com.playfieldportal.feature.achievements.provider.steam.SteamGlobalPct
import com.playfieldportal.feature.achievements.provider.steam.SteamGlobalResponse
import com.playfieldportal.feature.achievements.provider.steam.SteamGlobalWrap
import com.playfieldportal.feature.achievements.provider.steam.SteamMetadata
import com.playfieldportal.feature.achievements.provider.steam.SteamSchemaAchievement
import com.playfieldportal.feature.achievements.provider.steam.SteamSchemaResponse
import com.playfieldportal.feature.achievements.provider.steam.SteamWebApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import retrofit2.Response
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LocalSteamSourceTest {

    private val discovery = mockk<LocalSteamDiscovery>()
    private val webApi = mockk<SteamWebApi>()
    private val credentials = mockk<AchievementCredentialsProvider>()
    // Enrichment is exercised in its own test; here it passes coins through unchanged.
    private val hiddenDescriptions = mockk<LocalSteamHiddenDescriptions> {
        coEvery { enrich(any(), any()) } answers { secondArg() }
    }
    // Nothing cached: these cases exercise the network path that fills the metadata cache.
    private val metadataStore = mockk<com.playfieldportal.feature.achievements.provider.steam.SteamMetadataStore>(relaxed = true)
    // Nothing stored yet unless a test says so.
    private val coinDao = mockk<AccountAchievementDao> {
        coEvery { getForSet(any(), any()) } returns emptyList()
    }
    private val source =
        LocalSteamSource(discovery, webApi, credentials, hiddenDescriptions, metadataStore, coinDao, clock = { 0L })

    private val progressUri = mockk<Uri>()
    private val game = LocalSteamGame("MARVEL Cosmic Invasion", "doc:games/marvel", "2753970", progressUri)

    private fun schemaOf(vararg names: String) = Response.success(
        SteamSchemaResponse(SteamGame(SteamGameStats(names.map { SteamSchemaAchievement(name = it, displayName = it) }))),
    )

    init {
        // Stubbed outside the mockk { } block: there, get(...) resolves to MockK's dynamic-call DSL.
        coEvery { metadataStore.get(any()) } returns null
        coEvery { credentials.steamApiKey() } returns "key"
        coEvery { discovery.findByAppId("2753970") } returns game
        coEvery { webApi.getGlobalAchievementPercentages("2753970") } returns Response.success(
            SteamGlobalResponse(SteamGlobalWrap(listOf(SteamGlobalPct("ReadyForBattle", 62.0)))),
        )
    }

    @Test
    fun `joins local earned state to the Steam schema`() = runTest {
        coEvery { webApi.getSchemaForGame("key", "2753970") } returns schemaOf("ReadyForBattle", "StoppedThanos")
        coEvery { discovery.readProgress(progressUri) } returns listOf(
            EmuEarnedAchievement("ReadyForBattle", earned = true, earnedAtEpochSeconds = 1784120702L),
            EmuEarnedAchievement("StoppedThanos", earned = false, earnedAtEpochSeconds = null),
        )

        val result = assertIs<ProviderSyncResult.Success>(source.fetch("2753970"))

        val byId = result.coins.associateBy { it.providerAchievementId }
        assertEquals(2, byId.size)
        assertTrue(byId.getValue("ReadyForBattle").isEarned)
        assertEquals(1784120702_000L, byId.getValue("ReadyForBattle").earnedAt)
        assertEquals(62.0, byId.getValue("ReadyForBattle").globalRarity)
        assertEquals(false, byId.getValue("StoppedThanos").isEarned)
    }

    @Test
    fun `a game the emu knows but Steam has no schema for is NotFound`() = runTest {
        coEvery { webApi.getSchemaForGame("key", "2753970") } returns schemaOf()
        coEvery { discovery.readProgress(progressUri) } returns emptyList()

        assertIs<ProviderSyncResult.NotFound>(source.fetch("2753970"))
    }

    @Test
    fun `missing key never touches discovery or the network`() = runTest {
        coEvery { credentials.steamApiKey() } returns null
        assertIs<ProviderSyncResult.MissingCredentials>(source.fetch("2753970"))
    }

    @Test
    fun `no discovered folder is a typed failure`() = runTest {
        coEvery { discovery.findByAppId("999") } returns null
        assertIs<ProviderSyncResult.Failed>(source.fetch("999"))
    }

    @Test
    fun `no progress file tracks the set at zero earned instead of failing`() = runTest {
        // No save redirect / never played: the game still appears under All Tracked, at 0%.
        coEvery { discovery.findByAppId("2753970") } returns game.copy(achievementsUri = null)
        coEvery { webApi.getSchemaForGame("key", "2753970") } returns schemaOf("ReadyForBattle", "StoppedThanos")

        val result = assertIs<ProviderSyncResult.Success>(source.fetch("2753970"))

        assertEquals(2, result.coins.size)
        assertTrue(result.coins.none { it.isEarned })
    }

    // --- Cached path: hidden-description backfill (AD-5) ---

    private fun cacheSchema(vararg entries: SteamSchemaAchievement) {
        coEvery { metadataStore.get("2753970") } returns SteamMetadata(entries.toList(), emptyMap(), fetchedAt = 0L)
        coEvery { discovery.readProgress(progressUri) } returns emptyList()
    }

    private val hiddenBlank = SteamSchemaAchievement(name = "Secret", displayName = "Secret", hidden = 1)
    private val visible = SteamSchemaAchievement(name = "Open", displayName = "Open", description = "Plain.")

    @Test
    fun `a cached sync with a blank hidden coin enriches it`() = runTest {
        cacheSchema(hiddenBlank, visible)
        coEvery { hiddenDescriptions.enrich("2753970", any()) } answers {
            secondArg<List<SyncedCoin>>()
                .map { if (it.isHidden) it.copy(description = "Backfilled.") else it }
        }

        val result = assertIs<ProviderSyncResult.Success>(source.fetch("2753970"))

        assertEquals("Backfilled.", result.coins.single { it.providerAchievementId == "Secret" }.description)
        coVerify(exactly = 0) { webApi.getSchemaForGame(any(), any()) }
    }

    @Test
    fun `a cached sync with no blank hidden coin never enriches`() = runTest {
        cacheSchema(visible, hiddenBlank.copy(description = "Steam shipped this one."))

        source.fetch("2753970")

        coVerify(exactly = 0) { hiddenDescriptions.enrich(any(), any()) }
    }

    @Test
    fun `the launch-return check never enriches`() = runTest {
        cacheSchema(hiddenBlank)
        coEvery { discovery.readProgressOrNull(progressUri) } returns emptyList()

        val read = assertIs<LocalEarnedRead.Read>(source.readEarned("2753970"))
        source.mapFromCache("2753970", read)

        coVerify(exactly = 0) { hiddenDescriptions.enrich(any(), any()) }
    }

    @Test
    fun `a cached sync whose stored rows already describe every hidden coin makes no enrichment call`() = runTest {
        cacheSchema(hiddenBlank)
        coEvery { coinDao.getForSet("LOCAL_STEAM", "2753970") } returns listOf(
            AccountAchievementEntity(
                provider = "LOCAL_STEAM", providerGameId = "2753970", providerAchievementId = "Secret",
                title = "Secret", description = "Learned last week.", tier = "GOLD", globalRarity = 3.0,
                isHidden = true,
            ),
        )

        val result = assertIs<ProviderSyncResult.Success>(source.fetch("2753970"))

        assertEquals("Learned last week.", result.coins.single().description)
        coVerify(exactly = 0) { hiddenDescriptions.enrich(any(), any()) }
    }
}
