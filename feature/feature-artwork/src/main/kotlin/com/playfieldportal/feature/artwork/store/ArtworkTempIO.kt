package com.playfieldportal.feature.artwork.store

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import timber.log.Timber
import java.io.File
import java.io.InputStream

/**
 * Shared temp-file download/validation used by both artwork store backends: bytes stream into a
 * cache temp file and are magic-byte-checked for the kind before any backend commits them under
 * a real name — a CDN error page or truncated download is never visible at an artwork path.
 */
object ArtworkTempIO {

    suspend fun downloadToTemp(httpClient: HttpClient, cacheDir: File, kind: ArtworkKind, url: String): File? =
        runCatching {
            val response = httpClient.get(url)
            if (!response.status.isSuccess()) {
                Timber.w("Artwork download failed (${response.status.value}) for $url")
                return null
            }
            response.bodyAsChannel().toInputStream().use { copyToTemp(it, cacheDir, kind) }
        }.onFailure { Timber.w(it, "Artwork download error for $url") }.getOrNull()

    // Per-kind download ceilings. No legitimate scraper asset comes close (covers are a few MB,
    // manuals tens of MB); the cap is what stops a hostile or broken server from streaming
    // unbounded bytes into the cache partition.
    private const val MAX_IMAGE_BYTES = 50L * 1024 * 1024
    private const val MAX_MEDIA_BYTES = 200L * 1024 * 1024

    fun maxBytesFor(kind: ArtworkKind): Long = when (kind) {
        ArtworkKind.MANUAL, ArtworkKind.VIDEO -> MAX_MEDIA_BYTES
        else -> MAX_IMAGE_BYTES
    }

    /**
     * Streams [input] into a cache temp file; null if empty, over the per-kind size cap, or the
     * wrong payload type for [kind]. An over-cap stream is abandoned mid-copy, never fully drained.
     */
    fun copyToTemp(input: InputStream, cacheDir: File, kind: ArtworkKind): File? {
        val tmp = File.createTempFile("artwork_", ".part", cacheDir)
        val maxBytes = maxBytesFor(kind)
        val ok = runCatching {
            var total = 0L
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n == -1) break
                    total += n
                    if (total > maxBytes) {
                        Timber.w("Artwork payload rejected — exceeds ${maxBytes / (1024 * 1024)} MB cap for $kind")
                        return@runCatching false
                    }
                    out.write(buf, 0, n)
                }
            }
            total > 0 && PayloadCheck.accepts(kind, headerOf(tmp))
        }.getOrDefault(false)
        if (!ok) {
            Timber.w("Artwork payload rejected for $kind")
            tmp.delete()
            return null
        }
        return tmp
    }

    fun headerOf(file: File): ByteArray = runCatching {
        val header = ByteArray(12)
        val read = file.inputStream().use { it.read(header) }
        if (read <= 0) ByteArray(0) else header.copyOf(read)
    }.getOrDefault(ByteArray(0))
}
