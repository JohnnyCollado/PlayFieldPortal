package com.playfieldportal.feature.achievements.provider.steam

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class SteamRemoteDataSourceTest {

    private val webApi = mockk<SteamWebApi>()
    private val communityApi = mockk<SteamCommunityApi>()
    private val credentials = mockk<AchievementCredentialsProvider>()
    private val dataSource = SteamRemoteDataSource(webApi, communityApi, credentials)

    private fun creds(key: String? = "steam-key", id: String? = "76561197960287930") {
        coEvery { credentials.steamApiKey() } returns key
        coEvery { credentials.steamId64() } returns id
    }

    private fun schemaResponse(vararg names: String) = Response.success(
        SteamSchemaResponse(
            SteamGame(SteamGameStats(names.map { SteamSchemaAchievement(name = it, displayName = it) })),
        ),
    )

    @Test
    fun `fetch returns MissingCredentials without a key`() = runTest {
        creds(key = null)
        assertEquals(ProviderSyncResult.MissingCredentials, dataSource.fetch("440"))
    }

    @Test
    fun `fetch returns NotFound when the game has no achievements`() = runTest {
        creds()
        coEvery { webApi.getSchemaForGame(any(), "440") } returns Response.success(SteamSchemaResponse())
        assertEquals(ProviderSyncResult.NotFound, dataSource.fetch("440"))
    }

    @Test
    fun `a 403 player response maps to ProfileNotPublic`() = runTest {
        creds()
        coEvery { webApi.getSchemaForGame(any(), "440") } returns schemaResponse("a1")
        coEvery { webApi.getGlobalAchievementPercentages("440") } returns Response.success(SteamGlobalResponse())
        coEvery { webApi.getPlayerAchievements(any(), any(), "440") } returns
            Response.error(403, "{}".toResponseBody())
        assertEquals(ProviderSyncResult.ProfileNotPublic, dataSource.fetch("440"))
    }

    @Test
    fun `a successful fetch maps schema, rarity, and earned state to coins`() = runTest {
        creds()
        coEvery { webApi.getSchemaForGame(any(), "440") } returns schemaResponse("a1", "a2")
        coEvery { webApi.getGlobalAchievementPercentages("440") } returns Response.success(
            SteamGlobalResponse(SteamGlobalWrap(listOf(SteamGlobalPct("a1", 4.0), SteamGlobalPct("a2", 60.0)))),
        )
        coEvery { webApi.getPlayerAchievements(any(), any(), "440") } returns Response.success(
            SteamPlayerResponse(
                SteamPlayerStats(
                    success = true,
                    achievements = listOf(SteamPlayerAchievement("a1", achieved = 1, unlocktime = 1_700_000_000)),
                ),
            ),
        )

        val result = dataSource.fetch("440")

        assertTrue(result is ProviderSyncResult.Success)
        val coins = (result as ProviderSyncResult.Success).coins.associateBy { it.providerAchievementId }
        assertTrue(coins.getValue("a1").isEarned)
        assertEquals(4.0, coins.getValue("a1").globalRarity, 1e-6)
        assertTrue(!coins.getValue("a2").isEarned)
    }

    @Test
    fun `an earned hidden achievement's blank description is enriched from the community page`() = runTest {
        creds()
        val hidden = SteamSchemaAchievement(name = "h1", displayName = "A Moment's Respite", description = "", hidden = 1)
        coEvery { webApi.getSchemaForGame(any(), "440") } returns
            Response.success(SteamSchemaResponse(SteamGame(SteamGameStats(listOf(hidden)))))
        coEvery { webApi.getGlobalAchievementPercentages("440") } returns Response.success(SteamGlobalResponse())
        coEvery { webApi.getPlayerAchievements(any(), any(), "440") } returns Response.success(
            SteamPlayerResponse(
                SteamPlayerStats(success = true, achievements = listOf(SteamPlayerAchievement("h1", achieved = 1, unlocktime = 1))),
            ),
        )
        coEvery { communityApi.achievementsPage(any(), "440", any()) } returns Response.success(
            ("""<div class="achieveRow"><h3>A Moment's Respite</h3>""" +
                """<h5>Rest at the DigiBase for the first time.</h5></div>""").toResponseBody(),
        )

        val result = dataSource.fetch("440") as ProviderSyncResult.Success

        assertEquals("Rest at the DigiBase for the first time.", result.coins.single().description)
    }

    @Test
    fun `a failed community-page fetch keeps the coins untouched`() = runTest {
        creds()
        val hidden = SteamSchemaAchievement(name = "h1", displayName = "Secret", description = "", hidden = 1)
        coEvery { webApi.getSchemaForGame(any(), "440") } returns
            Response.success(SteamSchemaResponse(SteamGame(SteamGameStats(listOf(hidden)))))
        coEvery { webApi.getGlobalAchievementPercentages("440") } returns Response.success(SteamGlobalResponse())
        coEvery { webApi.getPlayerAchievements(any(), any(), "440") } returns Response.success(
            SteamPlayerResponse(
                SteamPlayerStats(success = true, achievements = listOf(SteamPlayerAchievement("h1", achieved = 1, unlocktime = 1))),
            ),
        )
        coEvery { communityApi.achievementsPage(any(), "440", any()) } throws RuntimeException("markup changed")

        val result = dataSource.fetch("440") as ProviderSyncResult.Success

        assertEquals("", result.coins.single().description) // placeholder path preserved
    }

    @Test
    fun `resolveVanity returns the id on success and null without a key`() = runTest {
        coEvery { credentials.steamApiKey() } returns "steam-key"
        coEvery { webApi.resolveVanityUrl(any(), "gaben") } returns Response.success(
            SteamVanityResponse(SteamVanityInner(steamid = "76561197960287930", success = 1)),
        )
        assertEquals("76561197960287930", dataSource.resolveVanity("gaben"))

        coEvery { credentials.steamApiKey() } returns null
        assertEquals(null, dataSource.resolveVanity("gaben"))
    }
}
