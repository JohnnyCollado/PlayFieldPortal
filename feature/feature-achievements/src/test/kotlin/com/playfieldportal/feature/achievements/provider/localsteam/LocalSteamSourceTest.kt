package com.playfieldportal.feature.achievements.provider.localsteam

import android.net.Uri
import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.provider.steam.SteamGame
import com.playfieldportal.feature.achievements.provider.steam.SteamGameStats
import com.playfieldportal.feature.achievements.provider.steam.SteamGlobalPct
import com.playfieldportal.feature.achievements.provider.steam.SteamGlobalResponse
import com.playfieldportal.feature.achievements.provider.steam.SteamGlobalWrap
import com.playfieldportal.feature.achievements.provider.steam.SteamSchemaAchievement
import com.playfieldportal.feature.achievements.provider.steam.SteamSchemaResponse
import com.playfieldportal.feature.achievements.provider.steam.SteamWebApi
import io.mockk.coEvery
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
    private val source = LocalSteamSource(discovery, webApi, credentials, hiddenDescriptions)

    private val progressUri = mockk<Uri>()
    private val game = LocalSteamGame("MARVEL Cosmic Invasion", "doc:games/marvel", "2753970", progressUri)

    private fun schemaOf(vararg names: String) = Response.success(
        SteamSchemaResponse(SteamGame(SteamGameStats(names.map { SteamSchemaAchievement(name = it, displayName = it) }))),
    )

    init {
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
}
