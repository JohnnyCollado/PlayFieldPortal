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

    /** Streams [input] into a cache temp file; null if empty or the wrong payload type for [kind]. */
    fun copyToTemp(input: InputStream, cacheDir: File, kind: ArtworkKind): File? {
        val tmp = File.createTempFile("artwork_", ".part", cacheDir)
        val ok = runCatching {
            tmp.outputStream().use { input.copyTo(it) }
            tmp.length() > 0 && PayloadCheck.accepts(kind, headerOf(tmp))
        }.getOrDefault(false)
        if (!ok) {
            Timber.w("Artwork payload rejected — empty or wrong type for $kind")
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
