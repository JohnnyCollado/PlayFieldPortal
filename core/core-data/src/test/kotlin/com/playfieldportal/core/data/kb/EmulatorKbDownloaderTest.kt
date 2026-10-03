package com.playfieldportal.core.data.kb

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmulatorKbDownloaderTest {

    private val url = EmulatorKbDownloader.MANIFEST_URL

    private class Fixture(val downloader: EmulatorKbDownloader, val requests: MutableList<io.ktor.client.request.HttpRequestData>)

    private fun fixture(handler: MockRequestHandler): Fixture {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        return Fixture(EmulatorKbDownloader(HttpClient(engine) { expectSuccess = false }), requests)
    }

    @Test
    fun `constants point at the fixed official assets`() {
        assertEquals(
            "https://github.com/JohnnyCollado/PlayFieldPortal/releases/download/emulator-kb/emulators.json",
            EmulatorKbDownloader.MANIFEST_URL,
        )
        assertEquals("${EmulatorKbDownloader.MANIFEST_URL}.sig", EmulatorKbDownloader.SIGNATURE_URL)
    }

    @Test
    fun `a 200 under the cap returns the bytes`() = runTest {
        val f = fixture { respond(content = byteArrayOf(1, 2, 3), status = HttpStatusCode.OK) }
        val result = f.downloader.fetch(url, cap = 10)
        assertIs<KbDownload.Bytes>(result)
        assertContentEquals(byteArrayOf(1, 2, 3), result.bytes)
    }

    @Test
    fun `a body exactly at the cap is accepted`() = runTest {
        val f = fixture { respond(content = ByteArray(100), status = HttpStatusCode.OK) }
        assertIs<KbDownload.Bytes>(f.downloader.fetch(url, cap = 100))
    }

    @Test
    fun `an over-cap body without Content-Length is too large`() = runTest {
        val f = fixture {
            // A channel body carries no Content-Length, so only the streaming cap can stop it.
            respond(content = ByteReadChannel(ByteArray(5_000)), status = HttpStatusCode.OK)
        }
        val result = f.downloader.fetch(url, cap = 1_000)
        assertIs<KbDownload.Failure>(result)
        assertEquals("too large", result.reason)
    }

    @Test
    fun `404 is a failure`() = runTest {
        val f = fixture { respondError(HttpStatusCode.NotFound) }
        assertIs<KbDownload.Failure>(f.downloader.fetch(url, cap = 1_000))
    }

    @Test
    fun `an IOException is a failure`() = runTest {
        val f = fixture { throw IOException("boom") }
        assertIs<KbDownload.Failure>(f.downloader.fetch(url, cap = 1_000))
    }

    @Test
    fun `an http URL is refused before any request`() = runTest {
        val f = fixture { respond(content = byteArrayOf(1), status = HttpStatusCode.OK) }
        assertIs<KbDownload.Failure>(f.downloader.fetch("http://example.com/emulators.json", cap = 1_000))
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun `a malformed URL is a failure not a throw`() = runTest {
        val f = fixture { respond(content = byteArrayOf(1), status = HttpStatusCode.OK) }
        assertIs<KbDownload.Failure>(f.downloader.fetch("not a url", cap = 1_000))
        assertTrue(f.requests.isEmpty())
    }

    @Test
    fun `the request has no query and only the expected identifying headers`() = runTest {
        val f = fixture { respond(content = byteArrayOf(1), status = HttpStatusCode.OK) }
        f.downloader.fetch(url, cap = 1_000)
        val request = f.requests.single()
        assertEquals("", request.url.encodedQuery)
        assertEquals("PlayFieldPortal", request.headers[HttpHeaders.UserAgent])
        assertNull(request.headers[HttpHeaders.Authorization])
        assertNull(request.headers[HttpHeaders.Cookie])
    }

    @Test
    fun `a redirect to http is not followed`() = runTest {
        val f = fixture { request ->
            if (request.url.protocol.name == "https") {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "http://example.com/emulators.json"),
                )
            } else {
                respond(content = byteArrayOf(9), status = HttpStatusCode.OK)
            }
        }
        assertIs<KbDownload.Failure>(f.downloader.fetch(url, cap = 1_000))
        assertTrue(f.requests.none { it.url.protocol.name == "http" })
    }

    @Test
    fun `cancellation is rethrown`() = runTest {
        val f = fixture { throw CancellationException("cancelled") }
        assertFailsWith<CancellationException> { f.downloader.fetch(url, cap = 1_000) }
    }

    @Test
    fun `an https to https redirect is followed with the shipped client config`() = runTest {
        val engine = MockEngine { request ->
            if (request.url.host == "github.com") {
                respond(
                    content = "",
                    status = HttpStatusCode.Found,
                    headers = headersOf(HttpHeaders.Location, "https://objects.githubusercontent.com/emulators.json"),
                )
            } else {
                respond(content = byteArrayOf(7, 8), status = HttpStatusCode.OK)
            }
        }
        val downloader = EmulatorKbDownloader(HttpClient(engine) { configureEmulatorKbClient() })

        val result = downloader.fetch(url, cap = 1_000)

        assertIs<KbDownload.Bytes>(result)
        assertContentEquals(byteArrayOf(7, 8), result.bytes)
    }
}
