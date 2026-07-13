package com.playfieldportal.feature.achievements.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SteamAppListResolverTest {

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    // Steam storefront search shape: a real result carries the ™ in its name, plus non-app types.
    private val storeSearchJson = """
        {"total":3,"items":[
          {"type":"app","name":"RESONANCE OF FATE™/END OF ETERNITY™ 4K/HD EDITION","id":645730},
          {"type":"app","name":"Half-Life 2","id":220},
          {"type":"dlc","name":"Half-Life 2: Extra","id":999}
        ]}
    """.trimIndent()

    private fun resolver(): SteamAppListResolver {
        val engine = MockEngine { respond(storeSearchJson, HttpStatusCode.OK, jsonHeaders) }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        return SteamAppListResolver(client)
    }

    @Test
    fun `resolveAppId matches an exact name ignoring case, punctuation and trademark symbols`() = runTest {
        val r = resolver()
        // The ™ and slashes normalize away, so the full store title resolves exactly.
        assertEquals("645730", r.resolveAppId("resonance of fate / end of eternity 4k hd edition"))
        assertEquals("220", r.resolveAppId("Half-Life 2"))
    }

    @Test
    fun `resolveAppId returns null without an exact name match (never a fuzzy auto-link)`() = runTest {
        // "Half-Life" alone doesn't equal any result's normalized name.
        assertNull(resolver().resolveAppId("Half-Life"))
    }

    @Test
    fun `search returns app results in Steam order, skipping non-app types`() = runTest {
        val results = resolver().search("half life")

        assertEquals(listOf("645730", "220"), results.map { it.appId }) // the dlc row is dropped
    }

    @Test
    fun `search returns empty for a blank query`() = runTest {
        assertEquals(emptyList(), resolver().search("   "))
    }
}
