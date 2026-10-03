package com.playfieldportal.feature.artwork.match

import com.playfieldportal.feature.artwork.api.GogStorefrontApi
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
import org.junit.Test
import java.io.IOException

/**
 * GOG as a storefront provider. Every response is mocked, and the catalog and game bodies are the
 * raw captures in `src/test/resources/gog/` (2026-09-30), so what is asserted about a preset is
 * asserted about what GOG really sends.
 */
class GogMetadataProviderTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResource("/gog/$name")) { "missing fixture $name" }.readText()

    private sealed interface Reply {
        data class Body(val json: String) : Reply
        data class Status(val status: HttpStatusCode) : Reply
        data object Boom : Reply
    }

    private val requests = mutableListOf<Url>()

    private fun provider(
        cache: StorefrontSearchCache = StorefrontSearchCache(),
        reply: (Url) -> Reply,
    ): GogMetadataProvider {
        val engine = MockEngine { request ->
            requests += request.url
            when (val r = reply(request.url)) {
                is Reply.Body -> respond(
                    content = r.json,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                is Reply.Status -> respondError(r.status)
                Reply.Boom -> throw IOException("socket closed")
            }
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        return GogMetadataProvider(GogStorefrontApi(client), StorefrontRequestQueue(minIntervalMs = 0), cache)
    }

    private val emptyCatalog = """{"products":[]}"""

    private fun catalogOf(id: String, title: String) =
        """{"products":[{"id":"$id","title":"$title","productType":"game"}]}"""

    private fun Url.query(): String = parameters["query"].orEmpty()

    // -- Identity --------------------------------------------------------------

    @Test
    fun `it is the GOG store and needs no key`() = runTest {
        val gog = provider { Reply.Body(emptyCatalog) }

        assertEquals(Storefront.GOG, gog.store)
        assertTrue(gog.isAvailable())
    }

    // -- Search ----------------------------------------------------------------

    @Test
    fun `a search hit becomes a candidate with everything the scorer can compare`() = runTest {
        val candidates = (provider { Reply.Body(fixture("catalog-doom.json")) }
            .search(listOf("doom")) as StorefrontOutcome.Ok).value

        val doom = candidates.single { it.storeId == "1390579243" }
        assertEquals(Storefront.GOG, doom.store)
        assertEquals("DOOM (2016)", doom.title)
        assertEquals(2016, doom.releaseYear)
        assertEquals("id Software", doom.developer)
        assertEquals("Bethesda Softworks LLC", doom.publisher)
        assertTrue(doom.thumbUrl!!.startsWith("https://images.gog-statics.com/"))
    }

    @Test
    fun `several developers are one comma-separated name, as Steam's are`() = runTest {
        val candidates = (provider { Reply.Body(fixture("catalog-doom.json")) }
            .search(listOf("doom")) as StorefrontOutcome.Ok).value

        assertEquals("id Software, Nightdive Studios", candidates.single { it.title == "DOOM 64" }.developer)
    }

    @Test
    fun `dlc never becomes a candidate`() = runTest {
        val candidates = (provider { Reply.Body(fixture("catalog-doom.json")) }
            .search(listOf("doom")) as StorefrontOutcome.Ok).value

        assertEquals(5, candidates.size)
        assertFalse(candidates.any { it.title == "DOOM Soundtrack" })
    }

    @Test
    fun `an empty search is NoMatch and not a failure`() = runTest {
        assertEquals(
            StorefrontOutcome.NoMatch,
            provider { Reply.Body(fixture("catalog-no-hits.json")) }.search(listOf("a game nobody sells")),
        )
    }

    @Test
    fun `the broader query is only tried when the first finds nothing`() = runTest {
        val gog = provider { url ->
            if ("ultimate" in url.query()) Reply.Body(emptyCatalog)
            else Reply.Body(catalogOf("1207658924", "Control"))
        }

        val candidates = (gog.search(listOf("control ultimate edition", "control")) as StorefrontOutcome.Ok).value

        assertEquals("1207658924", candidates.single().storeId)
        assertEquals(2, requests.size)
    }

    @Test
    fun `a query that already answered stops the search there`() = runTest {
        val gog = provider { Reply.Body(catalogOf("1", "Control Ultimate Edition")) }

        gog.search(listOf("control ultimate edition", "control"))

        assertEquals(1, requests.size)
    }

    @Test
    fun `an answer is remembered, an empty one included`() = runTest {
        val cache = StorefrontSearchCache()
        val gog = provider(cache) { url ->
            if ("nothing" in url.query()) Reply.Body(emptyCatalog) else Reply.Body(catalogOf("1", "Found"))
        }

        gog.search(listOf("nothing", "found"))
        gog.search(listOf("nothing", "found"))

        // Two requests the first time, none the second.
        assertEquals(2, requests.size)
    }

    @Test
    fun `a failed search outranks an empty one`() = runTest {
        // "GOG timed out" and "GOG does not have this game" lead to opposite actions.
        val gog = provider { url ->
            if ("first" in url.query()) Reply.Boom else Reply.Body(emptyCatalog)
        }

        val outcome = gog.search(listOf("first", "second"))

        assertEquals(StorefrontOutcome.Failure(StorefrontFailure.NETWORK_ERROR), outcome)
    }

    @Test
    fun `rate limiting is reported as rate limiting`() = runTest {
        val outcome = provider { Reply.Status(HttpStatusCode.TooManyRequests) }.search(listOf("doom"))

        assertEquals(StorefrontOutcome.Failure(StorefrontFailure.RATE_LIMITED), outcome)
    }

    @Test
    fun `a failed search is not remembered as an empty one`() = runTest {
        var fail = true
        val gog = provider { if (fail) Reply.Boom else Reply.Body(catalogOf("1", "Found")) }

        gog.search(listOf("found"))
        fail = false
        val second = gog.search(listOf("found"))

        assertEquals("1", (second as StorefrontOutcome.Ok).value.single().storeId)
    }

    // -- Metadata --------------------------------------------------------------

    @Test
    fun `a game's preset is built from GOG's own fields`() = runTest {
        val preset = (provider { Reply.Body(fixture("game-1640424747-pack.json")) }
            .getMetadata("1640424747") as StorefrontOutcome.Ok).value

        assertEquals(MatchProvider.GOG, preset.provider)
        assertEquals("The Witcher 3: Wild Hunt — Remastered", preset.title)
        assertEquals("CD PROJEKT RED", preset.developer)
        assertEquals("CD PROJEKT RED", preset.publisher)
        assertEquals("Role-playing, Adventure, Fantasy", preset.genre)
        assertEquals("Mature 17+", preset.ageRating)
    }

    @Test
    fun `the release date is the game's, never the day GOG listed it`() = runTest {
        // globalReleaseDate is 2016-08-30; gogReleaseDate, on the same product, is the 29th.
        val preset = (provider { Reply.Body(fixture("game-1640424747-pack.json")) }
            .getMetadata("1640424747") as StorefrontOutcome.Ok).value

        assertEquals(2016, preset.releaseYear)
        assertEquals("2016-08-30", preset.releaseDate)
    }

    @Test
    fun `a description comes through as plain text`() = runTest {
        val preset = (provider { Reply.Body(fixture("game-1441199941-game.json")) }
            .getMetadata("1441199941") as StorefrontOutcome.Ok).value

        val description = preset.description!!
        assertTrue(description.startsWith("Please note that you are required to own The Witcher 3: Wild Hunt"))
        assertTrue(description.contains("About The Witcher 3 REDkit:"))
        // Game Detail renders text, so no markup and no link targets may survive.
        assertFalse(description.contains('<'))
        assertFalse(description.contains("href"))
        assertFalse(description.contains("https://www.gog.com"))
    }

    @Test
    fun `a description that is only images is absent, not an empty string`() = runTest {
        // The Witcher 3's store description is banner images and nothing else.
        val preset = (provider { Reply.Body(fixture("game-1640424747-pack.json")) }
            .getMetadata("1640424747") as StorefrontOutcome.Ok).value

        assertNull(preset.description)
    }

    @Test
    fun `GOG supplies no franchise and no community rating, so neither is invented`() = runTest {
        val preset = (provider { Reply.Body(fixture("game-1640424747-pack.json")) }
            .getMetadata("1640424747") as StorefrontOutcome.Ok).value

        assertNull(preset.franchise)
        assertNull(preset.communityRating)
    }

    @Test
    fun `a game with no board rating has no age rating`() = runTest {
        val preset = (provider { Reply.Body(fixture("game-1441199941-game.json")) }
            .getMetadata("1441199941") as StorefrontOutcome.Ok).value

        assertNull(preset.ageRating)
        assertEquals("CD PROJEKT RED, Yigsoft", preset.developer)
        assertEquals(2024, preset.releaseYear)
    }

    @Test
    fun `a PEGI rating is used when there is no ESRB one`() = runTest {
        val body = """{"_embedded":{"product":{"id":5,"title":"Some Game"},"pegiRating":{"ageRating":12}}}"""

        val preset = (provider { Reply.Body(body) }.getMetadata("5") as StorefrontOutcome.Ok).value

        assertEquals("PEGI 12", preset.ageRating)
    }

    @Test
    fun `an id GOG does not know is NoMatch`() = runTest {
        assertEquals(
            StorefrontOutcome.NoMatch,
            provider { Reply.Status(HttpStatusCode.NotFound) }.getMetadata("999999999999"),
        )
    }

    @Test
    fun `an id that cannot be a GOG product id is NoMatch without asking`() = runTest {
        val gog = provider { Reply.Body(emptyCatalog) }

        assertEquals(StorefrontOutcome.NoMatch, gog.getMetadata("the_witcher_3"))
        assertEquals(StorefrontOutcome.NoMatch, gog.getMetadata(""))
        assertEquals(StorefrontOutcome.NoMatch, gog.getMetadata("1234567890123"))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun `an outage while fetching a game is a failure, not a missing game`() = runTest {
        assertEquals(
            StorefrontOutcome.Failure(StorefrontFailure.NETWORK_ERROR),
            provider { Reply.Boom }.getMetadata("1640424747"),
        )
    }

    // -- Identity validation ---------------------------------------------------

    @Test
    fun `an id is only declared gone when GOG says so`() = runTest {
        assertEquals(
            StorefrontOutcome.Ok(true),
            provider { Reply.Body(fixture("game-1640424747-pack.json")) }.validateIdentity("1640424747"),
        )
        assertEquals(
            StorefrontOutcome.Ok(false),
            provider { Reply.Status(HttpStatusCode.NotFound) }.validateIdentity("999999999999"),
        )
        // An outage must never unlink a library.
        assertEquals(
            StorefrontOutcome.Failure(StorefrontFailure.PROVIDER_ERROR),
            provider { Reply.Status(HttpStatusCode.InternalServerError) }.validateIdentity("1640424747"),
        )
    }

    // -- Plain text ------------------------------------------------------------

    @Test
    fun `markup is removed and its paragraphs kept apart`() {
        assertEquals(
            "First line.\n\nSecond line, with a link.",
            gogPlainText("<p class=\"module\">First line.</p><p>Second <b>line</b>, with a <a href=\"https://x\">link</a>.</p>"),
        )
        assertEquals("One\nTwo", gogPlainText("One<br>Two"))
    }

    @Test
    fun `entities are decoded`() {
        assertEquals("Tom & Jerry's \"big\" day <now>", gogPlainText("Tom &amp; Jerry&#39;s &quot;big&quot; day &lt;now&gt;"))
        assertEquals("a b", gogPlainText("a&nbsp;b"))
    }

    @Test
    fun `nothing readable is null`() {
        assertNull(gogPlainText("<p>\r\n<img src=\"https://images.gog.com/x.jpg\">\r\n</p>\r\n<hr>"))
        assertNull(gogPlainText(""))
        assertNull(gogPlainText(null))
    }
}
