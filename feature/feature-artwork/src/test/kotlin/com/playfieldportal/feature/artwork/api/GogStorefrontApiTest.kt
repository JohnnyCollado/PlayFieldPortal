package com.playfieldportal.feature.artwork.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * GOG's two public endpoints, against what they actually return.
 *
 * The fixtures under `src/test/resources/gog/` are raw responses captured on 2026-09-30, not
 * hand-written JSON: `catalog-doom.json` is `catalog.gog.com/v1/catalog?query=like:doom`, and the
 * `game-*.json` files are `api.gog.com/v2/games/{id}`. A field path asserted here is one GOG was
 * seen to send.
 */
class GogStorefrontApiTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/gog/$name")) { "missing fixture $name" }.readText()

    private sealed interface Reply {
        data class Body(val json: String) : Reply
        data class Status(val status: HttpStatusCode, val body: String = "") : Reply
        data class Throw(val error: Throwable) : Reply
    }

    private val requests = mutableListOf<Url>()

    private fun api(reply: (Url) -> Reply): GogStorefrontApi {
        val engine = MockEngine { request ->
            requests += request.url
            when (val r = reply(request.url)) {
                is Reply.Body -> respond(
                    content = r.json,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                is Reply.Status -> respondError(r.status, r.body)
                is Reply.Throw -> throw r.error
            }
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        return GogStorefrontApi(client)
    }

    private fun <T> GogResult<T>.value(): T = (this as GogResult.Ok<T>).value

    // -- Search ----------------------------------------------------------------

    @Test
    fun `search asks the catalog for titles like the term`() = runTest {
        api { Reply.Body(fixture("catalog-doom.json")) }.search("doom")

        val url = requests.single()
        assertEquals("catalog.gog.com", url.host)
        assertEquals("/v1/catalog", url.encodedPath)
        assertEquals("like:doom", url.parameters["query"])
    }

    @Test
    fun `search keeps games and packs and drops dlc`() = runTest {
        // A base game is often a `pack` on GOG, so filtering to `game` would lose the very
        // product the user owns. A soundtrack is not something a library game can be.
        val products = api { Reply.Body(fixture("catalog-doom.json")) }.search("doom").value()

        assertEquals(
            listOf("DOOM + DOOM II", "DOOM 64", "DOOM Eternal", "DOOM (2016)", "DOOM 3"),
            products.map { it.title },
        )
        assertFalse(products.any { it.title == "DOOM Soundtrack" })
    }

    @Test
    fun `a product id is read as the string GOG sends`() = runTest {
        val products = api { Reply.Body(fixture("catalog-doom.json")) }.search("doom").value()

        assertEquals("1390579243", products.single { it.title == "DOOM (2016)" }.id)
    }

    @Test
    fun `a search hit carries the evidence the scorer compares`() = runTest {
        val doom = api { Reply.Body(fixture("catalog-doom.json")) }.search("doom").value()
            .single { it.title == "DOOM (2016)" }

        // The game's own release date. `storeReleaseDate` on this same row is 2025.04.18, the day
        // GOG listed it — a year the scorer must never be handed.
        assertEquals("2016.05.13", doom.releaseDate)
        assertEquals(listOf("id Software"), doom.developers)
        assertEquals(listOf("Bethesda Softworks LLC"), doom.publishers)
        assertTrue(doom.coverHorizontal!!.startsWith("https://images.gog-statics.com/"))
    }

    @Test
    fun `a product type GOG adds later is not taken for a game`() = runTest {
        val body = """{"products":[
            {"id":"1","title":"Some Game","productType":"game"},
            {"id":"2","title":"Some Extras","productType":"extras"},
            {"id":"","title":"No Id","productType":"game"}
        ]}"""

        val products = api { Reply.Body(body) }.search("some").value()

        assertEquals(listOf("1"), products.map { it.id })
    }

    @Test
    fun `a catalog with no hits is an empty answer, not a failure`() = runTest {
        val result = api { Reply.Body(fixture("catalog-no-hits.json")) }.search("zzzqqxxnotagame")

        assertEquals(emptyList<GogCatalogProduct>(), result.value())
    }

    // -- One game --------------------------------------------------------------

    @Test
    fun `a game is fetched by id from the v2 endpoint`() = runTest {
        api { Reply.Body(fixture("game-1640424747-pack.json")) }.game("1640424747")

        val url = requests.single()
        assertEquals("api.gog.com", url.host)
        assertEquals("/v2/games/1640424747", url.encodedPath)
    }

    @Test
    fun `a game's details are read from where GOG puts them`() = runTest {
        val game = api { Reply.Body(fixture("game-1640424747-pack.json")) }.game("1640424747").value()!!
        val embedded = game.embedded!!

        assertEquals("The Witcher 3: Wild Hunt — Remastered", embedded.product?.title)
        assertEquals("2016-08-30T00:00:00+02:00", embedded.product?.globalReleaseDate)
        assertEquals("CD PROJEKT RED", embedded.publisher?.name)
        assertEquals(listOf("CD PROJEKT RED"), embedded.developers.map { it.name })
        assertEquals(listOf("Role-playing", "Adventure", "Fantasy"), embedded.tags.map { it.name })
        assertEquals("Mature 17+", embedded.esrbRating?.category?.name)
        assertEquals(18, embedded.pegiRating?.ageRating)
    }

    @Test
    fun `a game with no age rating simply has none`() = runTest {
        val game = api { Reply.Body(fixture("game-1441199941-game.json")) }.game("1441199941").value()!!

        assertNull(game.embedded?.esrbRating)
        assertNull(game.embedded?.pegiRating)
        assertEquals(listOf("CD PROJEKT RED", "Yigsoft"), game.embedded?.developers?.map { it.name })
        assertTrue(game.overview!!.contains("REDkit"))
    }

    @Test
    fun `an id GOG does not know is an honest empty, not a failure`() = runTest {
        val result = api { Reply.Status(HttpStatusCode.NotFound, fixture("game-404.json")) }.game("999999999999")

        assertNull(result.value())
    }

    // -- Failures --------------------------------------------------------------

    @Test
    fun `a 429 and a 403 are both rate limiting`() = runTest {
        assertEquals(
            GogResult.Failure(GogFailureKind.RATE_LIMITED),
            api { Reply.Status(HttpStatusCode.TooManyRequests) }.search("doom"),
        )
        assertEquals(
            GogResult.Failure(GogFailureKind.RATE_LIMITED),
            api { Reply.Status(HttpStatusCode.Forbidden) }.game("1"),
        )
    }

    @Test
    fun `a server error is a provider error`() = runTest {
        assertEquals(
            GogResult.Failure(GogFailureKind.PROVIDER_ERROR),
            api { Reply.Status(HttpStatusCode.InternalServerError) }.search("doom"),
        )
    }

    @Test
    fun `a 404 on the catalog is a provider error, because search has no such thing as an unknown id`() = runTest {
        assertEquals(
            GogResult.Failure(GogFailureKind.PROVIDER_ERROR),
            api { Reply.Status(HttpStatusCode.NotFound) }.search("doom"),
        )
    }

    @Test
    fun `a body that is not the expected shape is a provider error and never an empty result`() = runTest {
        // A changed response must read as "could not ask", or it would eventually be recorded as
        // "GOG does not have this game".
        assertEquals(
            GogResult.Failure(GogFailureKind.PROVIDER_ERROR),
            api { Reply.Body("""{"products":"not a list"}""") }.search("doom"),
        )
        assertEquals(
            GogResult.Failure(GogFailureKind.PROVIDER_ERROR),
            api { Reply.Body("<html>maintenance</html>") }.game("1"),
        )
    }

    @Test
    fun `a dropped connection is a network error`() = runTest {
        assertEquals(
            GogResult.Failure(GogFailureKind.NETWORK_ERROR),
            api { Reply.Throw(IOException("socket closed")) }.search("doom"),
        )
    }

    @Test
    fun `a cancelled call stays cancelled`() = runTest {
        // A cancelled scan is not a GOG outage.
        try {
            api { Reply.Throw(CancellationException("scan cancelled")) }.search("doom")
            fail("expected the cancellation to propagate")
        } catch (e: CancellationException) {
            assertEquals("scan cancelled", e.message)
        }
    }
}
