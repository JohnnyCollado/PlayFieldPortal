package com.playfieldportal.core.data.update

import com.playfieldportal.core.data.repository.SafeMedia.readCapped
import com.playfieldportal.core.domain.model.appupdate.AppReleaseParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/** Distinguishes the app-update client from every other HTTP client. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AppUpdateHttpClient

/**
 * Ktor client for the app's own update check and download. No logging, cookies or auth. There is
 * deliberately no whole-request timeout: an APK download takes as long as the connection allows,
 * and a stalled one is still cut off by the socket timeout.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppUpdateNetworkModule {

    @Provides
    @Singleton
    @AppUpdateHttpClient
    fun provideAppUpdateHttpClient(): HttpClient = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }
}

/** Outcome of [AppUpdateDownloader.fetchReleases]. */
sealed interface ReleaseListFetch {
    class Text(val text: String) : ReleaseListFetch
    data class Failure(val reason: String) : ReleaseListFetch
}

/** Outcome of [AppUpdateDownloader.downloadApk]. */
sealed interface ApkDownload {
    data object Done : ApkDownload
    data class Failure(val reason: String) : ApkDownload
}

/**
 * Talks to GitHub for the app's own updates: the public release list, and a release's APK. Public
 * repo, so no token; the only identifying header is `User-Agent: PlayFieldPortal`, which GitHub's
 * API requires. Both destinations are fixed — a download URL outside this repository's releases is
 * refused before any request is made.
 */
@Singleton
class AppUpdateDownloader @Inject constructor(
    @AppUpdateHttpClient private val http: HttpClient,
) {

    /** The raw release-list JSON, or why it could not be fetched. Cancellation propagates. */
    suspend fun fetchReleases(): ReleaseListFetch = withContext(Dispatchers.IO) {
        try {
            http.prepareGet(AppReleaseParser.RELEASES_API_URL) {
                header(HttpHeaders.UserAgent, USER_AGENT)
                header(HttpHeaders.Accept, "application/vnd.github+json")
            }.execute { response ->
                if (!response.status.isSuccess()) {
                    ReleaseListFetch.Failure("http ${response.status.value}")
                } else {
                    val bytes = response.bodyAsChannel().toInputStream().use { it.readCapped(MAX_RELEASE_LIST_BYTES) }
                    if (bytes == null) ReleaseListFetch.Failure("too large") else ReleaseListFetch.Text(String(bytes, Charsets.UTF_8))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ReleaseListFetch.Failure(e.javaClass.simpleName)
        }
    }

    /**
     * Streams the APK at [url] into [target]. The bytes go to a sibling temp file that only becomes
     * [target] once the whole body has arrived, so a failed or cancelled download leaves nothing
     * that could be mistaken for a finished one. [onProgress] gets 0..1 when [expectedBytes] is known.
     */
    suspend fun downloadApk(
        url: String,
        target: File,
        expectedBytes: Long,
        cap: Long = MAX_APK_BYTES,
        onProgress: (Float) -> Unit,
    ): ApkDownload = withContext(Dispatchers.IO) {
        if (!url.startsWith(DOWNLOAD_PREFIX)) return@withContext ApkDownload.Failure("unexpected url")
        val temp = File(target.parentFile, "${target.name}.part")
        try {
            target.parentFile?.mkdirs()
            target.delete()
            val result: ApkDownload = http.prepareGet(url) {
                header(HttpHeaders.UserAgent, USER_AGENT)
            }.execute { response ->
                if (!response.status.isSuccess()) return@execute ApkDownload.Failure("http ${response.status.value}")
                var total = 0L
                response.bodyAsChannel().toInputStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > cap) return@execute ApkDownload.Failure("too large")
                            output.write(buffer, 0, n)
                            if (expectedBytes > 0) onProgress((total.toFloat() / expectedBytes).coerceAtMost(1f))
                        }
                    }
                }
                if (total == 0L) ApkDownload.Failure("empty") else ApkDownload.Done
            }
            if (result == ApkDownload.Done && !temp.renameTo(target)) ApkDownload.Failure("storage") else result
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApkDownload.Failure(e.javaClass.simpleName)
        } finally {
            temp.delete()
        }
    }

    companion object {
        private const val DOWNLOAD_PREFIX = "${AppReleaseParser.RELEASES_PAGE_URL}/download/"
        const val MAX_RELEASE_LIST_BYTES = 2L * 1024 * 1024
        const val MAX_APK_BYTES = 400L * 1024 * 1024
        private const val USER_AGENT = "PlayFieldPortal"
    }
}
