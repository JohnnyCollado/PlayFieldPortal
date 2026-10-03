package com.playfieldportal.feature.artwork.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Steam's keyless store media — `IStoreBrowseService/GetItems` with assets, screenshots and
 * trailers — turned into absolute CDN URLs the Artwork Studio can show and download. Every
 * response is mocked; the payload shapes are copied from live answers (2026-09-29).
 */
class SteamStoreMediaTest {

    private val requests = mutableListOf<io.ktor.http.Url>()

    private fun api(respondWith: () -> Any): SteamStorefrontApi {
        val engine = MockEngine { request ->
            requests += request.url
            when (val r = respondWith()) {
                is String -> respond(
                    content = r,
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
                )
                is HttpStatusCode -> respondError(r)
                else -> throw IOException("socket closed")
            }
        }
        val client = HttpClient(engine) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; isLenient = true }) }
        }
        return SteamStorefrontApi(client)
    }

    // NieR:Automata: every asset at the app's root.
    private val plainItem = """
        {"response":{"store_items":[{"appid":524220,"success":1,
          "assets":{"asset_url_format":"steam/apps/524220/${'$'}{FILENAME}?t=1785182957",
            "main_capsule":"capsule_616x353.jpg","header":"header.jpg",
            "page_background":"page_bg_generated_v6b.jpg","hero_capsule":"hero_capsule.jpg",
            "library_capsule":"library_600x900.jpg","library_capsule_2x":"library_600x900_2x.jpg",
            "library_hero":"library_hero.jpg","library_hero_2x":"library_hero_2x.jpg"},
          "screenshots":{"all_ages_screenshots":[
            {"filename":"steam/apps/524220/ss_aaa.jpg?t=1785182957","ordinal":1},
            {"filename":"steam/apps/524220/ss_bbb.jpg?t=1785182957","ordinal":2}]},
          "trailers":{"highlights":[{"trailer_name":"Launch Trailer",
            "trailer_url_format":"steam/apps/${'$'}{FILENAME}?t=1551200572","trailer_base_id":210970,
            "microtrailer":[
              {"filename":"524220/210992/beb9/1750543704/microtrailer.webm","type":"video/webm"},
              {"filename":"524220/210992/beb9/1750543704/microtrailer.mp4","type":"video/mp4"}],
            "adaptive_trailers":[
              {"cdn_path":"524220/210992/beb9/1750543704/dash_h264.mpd","encoding":"dash_h264"},
              {"cdn_path":"524220/210992/beb9/1750543704/hls_264_master.m3u8","encoding":"hls_h264"}],
            "screenshot_full":"256744000/movie_full.jpg","all_ages":true}],
           "other_trailers":[{"trailer_name":"Streaming only","trailer_base_id":5,
            "trailer_url_format":"steam/apps/${'$'}{FILENAME}?t=9",
            "microtrailer":[],
            "adaptive_trailers":[{"cdn_path":"5/hls_264_master.m3u8","encoding":"hls_h264"}],
            "screenshot_full":"5/movie_full.jpg","all_ages":true},
           {"trailer_name":"Nothing to save","trailer_base_id":6,"microtrailer":[],
            "adaptive_trailers":[{"cdn_path":"6/dash_av1.mpd","encoding":"dash_av1"}],"all_ages":true}]}
        }]}}
    """.trimIndent()

    // Black Myth: Wukong: newer uploads live under a hash directory the URL must keep.
    private val hashedItem = """
        {"response":{"store_items":[{"appid":2358720,"success":1,
          "assets":{"asset_url_format":"steam/apps/2358720/${'$'}{FILENAME}?t=1760601605",
            "main_capsule":"f40e/capsule_616x353.jpg","header":"header.jpg",
            "library_capsule":"library_600x900.jpg"}}]}}
    """.trimIndent()

    private val assetBase = "https://shared.akamai.steamstatic.com/store_item_assets/"

    @Test
    fun `asks the keyless store browse service for one app with its media`() = runTest {
        api { plainItem }.storeMedia("524220")

        val url = requests.single()
        assertEquals("api.steampowered.com", url.host)
        assertEquals("/IStoreBrowseService/GetItems/v1", url.encodedPath)
        val input = url.parameters["input_json"].orEmpty()
        assertTrue(input.contains("\"appid\":524220"))
        assertTrue(input.contains("\"include_assets\":true"))
        assertTrue(input.contains("\"include_screenshots\":true"))
        assertTrue(input.contains("\"include_trailers\":true"))
        // No key parameter: none is needed, and none is ever sent.
        assertNull(url.parameters["key"])
    }

    @Test
    fun `library art resolves through the asset url format, preferring the 2x copies`() = runTest {
        val media = (api { plainItem }.storeMedia("524220") as SteamResult.Ok).value!!

        assertEquals(assetBase + "steam/apps/524220/library_600x900_2x.jpg?t=1785182957", media.libraryCapsule)
        assertEquals(assetBase + "steam/apps/524220/library_hero_2x.jpg?t=1785182957", media.libraryHero)
        assertEquals(assetBase + "steam/apps/524220/header.jpg?t=1785182957", media.header)
        assertEquals(assetBase + "steam/apps/524220/capsule_616x353.jpg?t=1785182957", media.mainCapsule)
        assertEquals(assetBase + "steam/apps/524220/page_bg_generated_v6b.jpg?t=1785182957", media.pageBackground)
    }

    @Test
    fun `a hashed asset keeps its hash directory`() = runTest {
        val media = (api { hashedItem }.storeMedia("2358720") as SteamResult.Ok).value!!

        assertEquals(assetBase + "steam/apps/2358720/f40e/capsule_616x353.jpg?t=1760601605", media.mainCapsule)
        // Only the 1x copy was listed, so that is what is used.
        assertEquals(assetBase + "steam/apps/2358720/library_600x900.jpg?t=1760601605", media.libraryCapsule)
        assertNull(media.libraryHero)
    }

    @Test
    fun `the logo is read from its fixed path because the service does not list it`() = runTest {
        val media = (api { hashedItem }.storeMedia("2358720") as SteamResult.Ok).value!!

        assertEquals(assetBase + "steam/apps/2358720/logo.png", media.logo)
    }

    @Test
    fun `screenshots come in order with a full image and a small thumbnail`() = runTest {
        val media = (api { plainItem }.storeMedia("524220") as SteamResult.Ok).value!!

        assertEquals(
            listOf(
                assetBase + "steam/apps/524220/ss_aaa.jpg?t=1785182957",
                assetBase + "steam/apps/524220/ss_bbb.jpg?t=1785182957",
            ),
            media.screenshots.map { it.url },
        )
        assertEquals(assetBase + "steam/apps/524220/ss_aaa.600x338.jpg?t=1785182957", media.screenshots.first().thumb)
    }

    @Test
    fun `a trailer offers its full HLS stream and its short mp4 cut, with its poster`() = runTest {
        val media = (api { plainItem }.storeMedia("524220") as SteamResult.Ok).value!!

        val trailer = media.trailers.first()
        assertEquals("Launch Trailer", trailer.name)
        assertEquals(
            "https://video.akamai.steamstatic.com/store_trailers/524220/210992/beb9/1750543704/hls_264_master.m3u8?t=1551200572",
            trailer.fullUrl,
        )
        assertEquals(
            "https://video.akamai.steamstatic.com/store_trailers/524220/210992/beb9/1750543704/microtrailer.mp4",
            trailer.shortUrl,
        )
        assertEquals(assetBase + "steam/apps/256744000/movie_full.jpg", trailer.poster)
    }

    @Test
    fun `a stream-only trailer is kept, and one with neither H264 HLS nor an mp4 is dropped`() = runTest {
        val media = (api { plainItem }.storeMedia("524220") as SteamResult.Ok).value!!

        assertEquals(listOf("Launch Trailer", "Streaming only"), media.trailers.map { it.name })
        val streamOnly = media.trailers[1]
        assertEquals("https://video.akamai.steamstatic.com/store_trailers/5/hls_264_master.m3u8?t=9", streamOnly.fullUrl)
        assertNull(streamOnly.shortUrl)
    }

    @Test
    fun `an app Steam does not know is an answer, not a failure`() = runTest {
        val result = api { """{"response":{"store_items":[{"appid":1,"success":2}]}}""" }.storeMedia("1")

        assertEquals(SteamResult.Ok(null), result)
    }

    @Test
    fun `transport, rate limit and a broken body are named failures`() = runTest {
        assertEquals(SteamResult.Failure(SteamFailureKind.NETWORK_ERROR), api { Unit }.storeMedia("1"))
        assertEquals(
            SteamResult.Failure(SteamFailureKind.RATE_LIMITED),
            api { HttpStatusCode.TooManyRequests }.storeMedia("1"),
        )
        assertEquals(SteamResult.Failure(SteamFailureKind.PROVIDER_ERROR), api { "{not json" }.storeMedia("1"))
    }
}
