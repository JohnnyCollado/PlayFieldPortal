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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RetroAchievementsApiTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    // Counts as JSON strings — the older RA response shape the client must tolerate.
    private val gameJson = """
        {"ID":14402,"Title":"Chrono Trigger","NumDistinctPlayersCasual":"100","Achievements":{
          "1":{"ID":1,"Title":"A","Description":"da","NumAwarded":"2","DateEarnedHardcore":"2024-01-02 03:04:05","BadgeName":"111"},
          "2":{"ID":2,"Title":"B","Description":"db","NumAwarded":"40","BadgeName":"222"}
        }}
    """.trimIndent()

    private fun api(username: String? = "Chrono", key: String? = "ra-key"): RetroAchievementsApi {
        val engine = MockEngine { respond(gameJson, HttpStatusCode.OK, jsonHeaders) }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        val creds = mockk<AchievementCredentialsProvider> {
            coEvery { raUsername() } returns username
            coEvery { raApiKey() } returns key
        }
        return RetroAchievementsApi(client, creds)
    }

    @Test
    fun `derives rarity from award counts and reads earned dates`() = runTest {
        val result = api().fetch("14402")
        assertTrue(result is ProviderSyncResult.Success)
        val coins = (result as ProviderSyncResult.Success).coins.associateBy { it.providerAchievementId }

        val a = coins.getValue("1")
        assertEquals(ShibaTier.GOLD, a.tier)          // 2/100 = 2% -> Gold
        assertTrue(a.isEarned)
        assertNotNull(a.earnedAt)
        assertTrue(a.iconUrl!!.endsWith("/111.png"))

        val b = coins.getValue("2")
        assertEquals(ShibaTier.BRONZE, b.tier)        // 40/100 = 40% -> Bronze
        assertTrue(!b.isEarned)
        assertNull(b.earnedAt)
    }

    @Test
    fun `missing key short-circuits to MissingCredentials`() = runTest {
        assertEquals(ProviderSyncResult.MissingCredentials, api(key = null).fetch("14402"))
    }

    private fun listApi(listJson: String): RetroAchievementsApi {
        val engine = MockEngine { respond(listJson, HttpStatusCode.OK, jsonHeaders) }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        val creds = mockk<AchievementCredentialsProvider> {
            coEvery { raUsername() } returns "u"
            coEvery { raApiKey() } returns "k"
        }
        return RetroAchievementsApi(client, creds)
    }

    @Test
    fun `gameIdForHash matches a rom hash to a game id, case-insensitively`() = runTest {
        val api = listApi(
            """[{"ID":14402,"Hashes":["ABCDEF0123","0011"]},{"ID":555,"Hashes":["deadbeef"]}]""",
        )

        assertEquals("14402", api.gameIdForHash(7, "abcdef0123"))
        assertEquals("555", api.gameIdForHash(7, "DEADBEEF"))
        assertNull(api.gameIdForHash(7, "nope"))
    }
}
