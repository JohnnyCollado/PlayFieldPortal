package com.playfieldportal.feature.artwork.match

import com.playfieldportal.feature.artwork.api.SteamStorefrontApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The Steam provider (C23 T6, Phase 21). Every response is mocked — nothing here touches the
 * network, so the suite is as green on a plane as it is in CI.
 *
 * The cases are the ones Phase 21 names: search, a successful lookup, no match, several matches,
 * a network failure, a malformed body, rate limiting, a cached response and a request that is
 * deduplicated rather than issued twice.
 */
class SteamMetadataProviderTest {

    // -- Fixtures ---------------------------------------------------------------

    private class Recorder {
        val paths = mutableListOf<String>()
    }

    private fun providerFor(
        recorder: Recorder = Recorder(),
        queue: StorefrontRequestQueue = StorefrontRequestQueue(minIntervalMs = 0),
        cache: StorefrontSearchCache = StorefrontSearchCache(),
        respondWith: (path: String, query: String) -> MockResponse,
    ): SteamMetadataProvider {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            recorder.paths += request.url.toString()
            when (val response = respondWith(path, request.url.encodedQuery)) {
                is MockResponse.Body -> respond(
                    content = response.json,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                is MockResponse.Status -> respondError(response.status)
                is MockResponse.Boom -> throw IOException("socket closed")
            }
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        return SteamMetadataProvider(SteamStorefrontApi(client), queue, cache)
    }

    private sealed interface MockResponse {
        data class Body(val json: String) : MockResponse
        data class Status(val status: HttpStatusCode) : MockResponse
        data object Boom : MockResponse
    }

    /** One `storesearch` payload. Built by concatenation: Kotlin cannot nest raw strings. */
    private fun searchBody(vararg items: Triple<Long, String, String>): String {
        val rows = items.joinToString(",") { (id, name, type) ->
            "{\"id\":" + id + ",\"name\":\"" + name + "\",\"type\":\"" + type +
                "\",\"tiny_image\":\"https://img/" + id + ".jpg\"}"
        }
        return "{\"total\":" + items.size + ",\"items\":[" + rows + "]}"
    }

    private val portal2Details = """
        {"620":{"success":true,"data":{
          "type":"game","name":"Portal 2","steam_appid":620,"required_age":0,
          "short_description":"A first-person puzzle game.",
          "developers":["Valve"],"publishers":["Valve"],
          "genres":[{"id":"1","description":"Action"},{"id":"25","description":"Adventure"}],
          "release_date":{"coming_soon":false,"date":"18 Apr, 2011"},
          "metacritic":{"score":95}
        }}}
    """.trimIndent()

    // -- Search -----------------------------------------------------------------

    @Test
    fun `search maps store items to candidates and drops non-app entries`() = runTest {
        val provider = providerFor {
            _, _ -> MockResponse.Body(
                searchBody(
                    Triple(620L, "Portal 2", "app"),
                    Triple(999L, "Portal Bundle", "bundle"),
                )
            )
        }

        val outcome = provider.search(listOf("portal 2"))

        val candidates = (outcome as StorefrontOutcome.Ok).value
        assertEquals(1, candidates.size)
        assertEquals("620", candidates.single().storeId)
        assertEquals(Storefront.STEAM, candidates.single().store)
    }

    @Test
    fun `search returns every app so the scorer can see the whole field`() = runTest {
        val provider = providerFor { _, _ ->
            MockResponse.Body(
                searchBody(
                    Triple(379720L, "DOOM", "app"),
                    Triple(2280L, "DOOM", "app"),
                    Triple(2300L, "DOOM II", "app"),
                )
            )
        }

        val candidates = (provider.search(listOf("doom")) as StorefrontOutcome.Ok).value

        assertEquals(3, candidates.size)
    }

    @Test
    fun `an empty search is NoMatch and not a failure`() = runTest {
        val provider = providerFor { _, _ -> MockResponse.Body("""{"total":0,"items":[]}""") }

        assertEquals(StorefrontOutcome.NoMatch, provider.search(listOf("a game nobody sells")))
    }

    @Test
    fun `the broader edition-stripped query is only tried when the first finds nothing`() = runTest {
        val recorder = Recorder()
        val provider = providerFor(recorder) { _, query ->
            if ("ultimate" in query) MockResponse.Body("""{"total":0,"items":[]}""")
            else MockResponse.Body(searchBody(Triple(870780L, "Control", "app")))
        }

        val candidates = (provider.search(
            listOf("control ultimate edition", "control")
        ) as StorefrontOutcome.Ok).value

        assertEquals("870780", candidates.single().storeId)
        assertEquals(2, recorder.paths.size)
    }

    @Test
    fun `a query that answers first costs exactly one request`() = runTest {
        val recorder = Recorder()
        val provider = providerFor(recorder) { _, _ ->
            MockResponse.Body(searchBody(Triple(870780L, "Control Ultimate Edition", "app")))
        }

        provider.search(listOf("control ultimate edition", "control"))

        // The second, broader query exists only to rescue a first that found nothing. Running it
        // anyway would spend a request to dilute a result that was already good.
        assertEquals(1, recorder.paths.size)
    }

    // -- Metadata ---------------------------------------------------------------

    @Test
    fun `appdetails becomes a metadata preset`() = runTest {
        val provider = providerFor { _, _ -> MockResponse.Body(portal2Details) }

        val preset = (provider.getMetadata("620") as StorefrontOutcome.Ok).value

        assertEquals(MatchProvider.STEAM, preset.provider)
        assertEquals("Portal 2", preset.title)
        assertEquals("A first-person puzzle game.", preset.description)
        assertEquals("Valve", preset.developer)
        assertEquals("Valve", preset.publisher)
        assertEquals("Action, Adventure", preset.genre)
        assertEquals(2011, preset.releaseYear!!.toInt())
        assertEquals("2011-04-18", preset.releaseDate)
        assertEquals(0.95f, preset.communityRating!!, 0.001f)
        // required_age 0 is "no gate", which is not a rating and must not be invented into one.
        assertNull(preset.ageRating)
    }

    @Test
    fun `an unparseable release date yields a year and no date rather than a fabricated one`() = runTest {
        val provider = providerFor { _, _ ->
            MockResponse.Body(
                """{"620":{"success":true,"data":{"name":"Some Game","release_date":{"coming_soon":false,"date":"Q3 2026"}}}}"""
            )
        }

        val preset = (provider.getMetadata("620") as StorefrontOutcome.Ok).value

        assertEquals(2026, preset.releaseYear!!.toInt())
        assertNull(preset.releaseDate)
    }

    @Test
    fun `steam saying success false is NoMatch, not an error`() = runTest {
        val provider = providerFor { _, _ -> MockResponse.Body("""{"123":{"success":false}}""") }

        assertEquals(StorefrontOutcome.NoMatch, provider.getMetadata("123"))
    }

    @Test
    fun `an id that is not an appid never leaves the device`() = runTest {
        val recorder = Recorder()
        val provider = providerFor(recorder) { _, _ -> MockResponse.Body(portal2Details) }

        assertEquals(StorefrontOutcome.NoMatch, provider.getMetadata("not-an-appid"))
        assertTrue(recorder.paths.isEmpty())
    }

    // -- Failures ---------------------------------------------------------------

    @Test
    fun `a network failure is NETWORK_ERROR and never NoMatch`() = runTest {
        val provider = providerFor { _, _ -> MockResponse.Boom }

        val failure = provider.search(listOf("portal 2")) as StorefrontOutcome.Failure

        assertEquals(StorefrontFailure.NETWORK_ERROR, failure.reason)
    }

    @Test
    fun `429 is reported as RATE_LIMITED so a bulk run can back off`() = runTest {
        val provider = providerFor { _, _ -> MockResponse.Status(HttpStatusCode.TooManyRequests) }

        val failure = provider.search(listOf("portal 2")) as StorefrontOutcome.Failure

        assertEquals(StorefrontFailure.RATE_LIMITED, failure.reason)
    }

    @Test
    fun `a 500 is a PROVIDER_ERROR`() = runTest {
        val provider = providerFor { _, _ -> MockResponse.Status(HttpStatusCode.InternalServerError) }

        val failure = provider.getMetadata("620") as StorefrontOutcome.Failure

        assertEquals(StorefrontFailure.PROVIDER_ERROR, failure.reason)
    }

    @Test
    fun `a malformed body is a PROVIDER_ERROR and not an empty result`() = runTest {
        // The distinction that matters: a shape change is something to retry, where an empty
        // result would eventually be remembered as "Steam does not have this game".
        val provider = providerFor { _, _ -> MockResponse.Body("""{"items": "not an array"}""") }

        val failure = provider.search(listOf("portal 2")) as StorefrontOutcome.Failure

        assertEquals(StorefrontFailure.PROVIDER_ERROR, failure.reason)
    }

    // -- Cache and deduplication -------------------------------------------------

    @Test
    fun `a repeated search is served from the cache`() = runTest {
        val recorder = Recorder()
        val provider = providerFor(recorder) { _, _ ->
            MockResponse.Body(searchBody(Triple(620L, "Portal 2", "app")))
        }

        provider.search(listOf("portal 2"))
        provider.search(listOf("portal 2"))

        assertEquals(1, recorder.paths.size)
    }

    @Test
    fun `a failure is never cached`() = runTest {
        val recorder = Recorder()
        var failFirst = true
        val provider = providerFor(recorder) { _, _ ->
            if (failFirst) { failFirst = false; MockResponse.Boom }
            else MockResponse.Body(searchBody(Triple(620L, "Portal 2", "app")))
        }

        assertTrue(provider.search(listOf("portal 2")) is StorefrontOutcome.Failure)
        // An outage must not be remembered as this game's answer for the next six hours.
        assertTrue(provider.search(listOf("portal 2")) is StorefrontOutcome.Ok)
        assertEquals(2, recorder.paths.size)
    }

    @Test
    fun `validateIdentity reports a live id, a gone id and an outage as three different things`() = runTest {
        val live = providerFor { _, _ -> MockResponse.Body(portal2Details) }
        assertTrue((live.validateIdentity("620") as StorefrontOutcome.Ok).value)

        val gone = providerFor { _, _ -> MockResponse.Body("""{"620":{"success":false}}""") }
        assertFalse((gone.validateIdentity("620") as StorefrontOutcome.Ok).value)

        val down = providerFor { _, _ -> MockResponse.Boom }
        // Crucially NOT `false`: an unreachable store must never be able to unlink a library.
        assertTrue(down.validateIdentity("620") is StorefrontOutcome.Failure)
    }
}
