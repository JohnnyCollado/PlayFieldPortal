package com.playfieldportal.core.data.kb

import com.playfieldportal.core.data.repository.SafeMedia.readCapped
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Outcome of [EmulatorKbDownloader.fetch]. */
sealed interface KbDownload {
    class Bytes(val bytes: ByteArray) : KbDownload
    data class Failure(val reason: String) : KbDownload
}

/**
 * Fetches the official knowledge-base assets. HTTPS only, public repo (no token), and the only
 * header sent is `User-Agent: PlayFieldPortal`. The body is read through a streaming cap because a
 * chunked response carries no Content-Length to check up front.
 */
@Singleton
class EmulatorKbDownloader @Inject constructor(
    @EmulatorKbHttpClient private val http: HttpClient,
) {
    /**
     * Returns the body, or a [KbDownload.Failure]. Cancellation propagates. Runs on [Dispatchers.IO]
     * because the body is read through a blocking stream.
     */
    suspend fun fetch(url: String, cap: Long): KbDownload = withContext(Dispatchers.IO) {
        val parsed = try {
            Url(url)
        } catch (e: IllegalArgumentException) {
            return@withContext KbDownload.Failure("invalid url")
        }
        if (parsed.protocol != URLProtocol.HTTPS) return@withContext KbDownload.Failure("https required")
        try {
            http.prepareGet(url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    KbDownload.Failure("http ${response.status.value}")
                } else {
                    val bytes = response.bodyAsChannel().toInputStream().use { it.readCapped(cap) }
                    if (bytes == null) KbDownload.Failure("too large") else KbDownload.Bytes(bytes)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            KbDownload.Failure(e.javaClass.simpleName)
        }
    }

    companion object {
        const val MANIFEST_URL =
            "https://github.com/JohnnyCollado/PlayFieldPortal/releases/download/emulator-kb/emulators.json"
        const val SIGNATURE_URL = "$MANIFEST_URL.sig"
        const val MAX_MANIFEST_BYTES = 1L * 1024 * 1024
        const val MAX_SIGNATURE_BYTES = 1024L
        private const val USER_AGENT = "PlayFieldPortal"
    }
}
