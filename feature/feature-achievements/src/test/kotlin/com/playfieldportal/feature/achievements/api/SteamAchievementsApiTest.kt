package com.playfieldportal.feature.achievements.api

import com.playfieldportal.core.data.achievement.AchievementCredentialsProvider
import com.playfieldportal.core.domain.achievement.ShibaTier
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SteamAchievementsApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private val schemaJson = """
        {"game":{"availableGameStats":{"achievements":[
          {"name":"ACH_A","displayName":"First","description":"do a","hidden":0,"icon":"a.png","icongray":"ag.png"},
          {"name":"ACH_B","displayName":"Second","description":"do b","hidden":1,"icon":"b.png","icongray":"bg.png"}
        ]}}}
    """.trimIndent()

    private val globalJson = """
        {"achievementpercentages":{"achievements":[
          {"name":"ACH_A","percent":2.0},
          {"name":"ACH_B","percent":40.0}
        ]}}
    """.trimIndent()

    private fun api(playerJson: String, playerStatus: HttpStatusCode = HttpStatusCode.OK): SteamAchievementsApi {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            when {
                "GetSchemaForGame" in path -> respond(schemaJson, HttpStatusCode.OK, jsonHeaders)
                "GetGlobalAchievementPercentagesForApp" in path -> respond(globalJson, HttpStatusCode.OK, jsonHeaders)
                "GetPlayerAchievements" in path -> respond(playerJson, playerStatus, jsonHeaders)
                else -> respond("{}", HttpStatusCode.OK, jsonHeaders)
            }
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        val creds = mockk<AchievementCredentialsProvider> {
            coEvery { steamApiKey() } returns "key"
            coEvery { steamId64() } returns "76561197960287930"
        }
        return SteamAchievementsApi(client, creds)
    }

    @Test
    fun `maps schema, rarity and earned state into coins`() = runTest {
        val playerJson = """
            {"playerstats":{"success":true,"achievements":[
              {"apiname":"ACH_A","achieved":1,"unlocktime":1000},
              {"apiname":"ACH_B","achieved":0,"unlocktime":0}
            ]}}
        """.trimIndent()

        val result = api(playerJson).fetch("440")
        assertTrue(result is ProviderSyncResult.Success)
        val coins = (result as ProviderSyncResult.Success).coins.associateBy { it.providerAchievementId }

        val a = coins.getValue("ACH_A")
        assertEquals(ShibaTier.GOLD, a.tier)   // 2% -> Gold
        assertTrue(a.isEarned)
        assertEquals(1_000_000L, a.earnedAt)   // seconds -> millis
        assertEquals("a.png", a.iconUrl)       // earned uses the colour icon

        val b = coins.getValue("ACH_B")
        assertEquals(ShibaTier.BRONZE, b.tier) // 40% -> Bronze
        assertTrue(b.isHidden)
        assertTrue(!b.isEarned)
        assertNull(b.earnedAt)
        assertEquals("bg.png", b.iconUrl)      // locked uses the grey icon
    }

    @Test
    fun `private profile is reported as ProfileNotPublic`() = runTest {
        val privateJson = """{"playerstats":{"error":"Profile is not public","success":false}}"""
        assertEquals(ProviderSyncResult.ProfileNotPublic, api(privateJson).fetch("440"))
    }

    @Test
    fun `missing key short-circuits to MissingCredentials`() = runTest {
        val engine = MockEngine { respond("{}", HttpStatusCode.OK, jsonHeaders) }
        val client = HttpClient(engine) { expectSuccess = false }
        val creds = mockk<AchievementCredentialsProvider> {
            coEvery { steamApiKey() } returns null
            coEvery { steamId64() } returns "76561197960287930"
        }
        assertEquals(ProviderSyncResult.MissingCredentials, SteamAchievementsApi(client, creds).fetch("440"))
    }
}
