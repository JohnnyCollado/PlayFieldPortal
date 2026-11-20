package com.playfieldportal.core.data.update

import com.playfieldportal.core.domain.model.appupdate.AppReleaseParser
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppUpdateDownloaderTest {

    private val apkUrl =
        "https://github.com/JohnnyCollado/PlayFieldPortal/releases/download/1.3.4/PlayFieldPortal-1.3.4-full.apk"
    private val dir: File = createTempDirectory("app_update_test").toFile()
    private val target get() = File(dir, "update.apk")

    private class Fixture(val downloader: AppUpdateDownloader, val requests: MutableList<HttpRequestData>)

    private fun fixture(handler: MockRequestHandler): Fixture {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        return Fixture(AppUpdateDownloader(HttpClient(engine) { expectSuccess = false }), requests)
    }

    // ── Release list ──────────────────────────────────────────────────────────

    @Test
    fun `the release list is asked for anonymously, naming only the app`() = runTest {
        val f = fixture { respond(content = "[]", status = HttpStatusCode.OK) }

        val result = f.downloader.fetchReleases()

        assertIs<ReleaseListFetch.Text>(result)
        assertEquals("[]", result.text)
        val request = f.requests.single()
        assertEquals(AppReleaseParser.RELEASES_API_URL, request.url.toString())
        assertEquals("PlayFieldPortal", request.headers[HttpHeaders.UserAgent])
        assertFalse(request.headers.contains(HttpHeaders.Authorization))
        assertFalse(request.headers.contains(HttpHeaders.Cookie))
    }

    @Test
    fun `a rate-limited or failing release list is a failure, not an empty list`() = runTest {
        val f = fixture { respondError(HttpStatusCode.Forbidden) }

        val result = f.downloader.fetchReleases()

        assertIs<ReleaseListFetch.Failure>(result)
        assertEquals("http 403", result.reason)
    }

    @Test
    fun `a network error on the release list is a failure`() = runTest {
        val f = fixture { throw IOException("offline") }

        assertIs<ReleaseListFetch.Failure>(f.downloader.fetchReleases())
    }

    // ── APK download ──────────────────────────────────────────────────────────

    @Test
    fun `an apk is written to the target and progress reaches the end`() = runTest {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val f = fixture { respond(content = ByteReadChannel(bytes), status = HttpStatusCode.OK) }
        val progress = mutableListOf<Float>()

        val result = f.downloader.downloadApk(apkUrl, target, expectedBytes = bytes.size.toLong()) { progress += it }

        assertEquals(ApkDownload.Done, result)
        assertContentEquals(bytes, target.readBytes())
        assertEquals(1f, progress.last())
        assertTrue(progress.zipWithNext().all { (a, b) -> a <= b }, "progress never goes backwards")
    }

    @Test
    fun `a download from anywhere but this repo's releases is refused without a request`() = runTest {
        val f = fixture { respond(content = "x", status = HttpStatusCode.OK) }

        val result = f.downloader.downloadApk("https://example.com/PlayFieldPortal-1.3.4-full.apk", target, 1) {}

        assertIs<ApkDownload.Failure>(result)
        assertTrue(f.requests.isEmpty())
        assertFalse(target.exists())
    }

    @Test
    fun `a body larger than the cap is dropped and leaves nothing behind`() = runTest {
        val f = fixture { respond(content = ByteReadChannel(ByteArray(50_000)), status = HttpStatusCode.OK) }

        val result = f.downloader.downloadApk(apkUrl, target, expectedBytes = 0, cap = 10_000) {}

        assertEquals(ApkDownload.Failure("too large"), result)
        assertFalse(target.exists())
        assertTrue(dir.listFiles().orEmpty().isEmpty(), "no partial file is left")
    }

    @Test
    fun `a failed download leaves nothing behind`() = runTest {
        val f = fixture { respondError(HttpStatusCode.NotFound) }

        val result = f.downloader.downloadApk(apkUrl, target, 1) {}

        assertEquals(ApkDownload.Failure("http 404"), result)
        assertTrue(dir.listFiles().orEmpty().isEmpty())
    }
}
