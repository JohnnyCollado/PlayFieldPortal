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

    private val appListJson = """
        {"applist":{"apps":[
          {"appid":220,"name":"Half-Life 2"},
          {"appid":440,"name":"Team Fortress 2"},
          {"appid":0,"name":""}
        ]}}
    """.trimIndent()

    private fun resolver(): SteamAppListResolver {
        val engine = MockEngine { respond(appListJson, HttpStatusCode.OK, jsonHeaders) }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        return SteamAppListResolver(client)
    }

    @Test
    fun `matches titles ignoring case and punctuation`() = runTest {
        val r = resolver()
        assertEquals("220", r.resolveAppId("half-life 2"))
        assertEquals("440", r.resolveAppId("Team Fortress 2"))
    }

    @Test
    fun `returns null when no title matches`() = runTest {
        assertNull(resolver().resolveAppId("Some Unlisted Game"))
    }
}
