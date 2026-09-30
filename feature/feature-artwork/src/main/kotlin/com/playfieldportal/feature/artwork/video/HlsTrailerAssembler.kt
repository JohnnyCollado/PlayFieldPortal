package com.playfieldportal.feature.artwork.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.playfieldportal.feature.artwork.store.ArtworkKind
import com.playfieldportal.feature.artwork.store.ArtworkTempIO
import com.playfieldportal.feature.artwork.store.PayloadCheck
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.cancellation.CancellationException

/**
 * Turns a streamed trailer (an HLS master playlist) into one ordinary mp4 the artwork stores can
 * keep — the way SteamPoacher saves Steam's full trailers, done with the platform's own tools.
 *
 * 1. The master names the renditions and the audio track; [HlsTrailerPlan] picks for the tab.
 * 2. Each track's init segment and chunks are appended into one file. For CMAF that concatenation
 *    is already a valid fragmented mp4.
 * 3. [MediaExtractor] reads both fragmented files and [MediaMuxer] writes their samples, in time
 *    order, into a plain mp4. No decode, no encode: the bytes Steam encoded are the bytes stored.
 *
 * Every download counts against one [maxBytes] budget, so a broken playlist can never fill the
 * cache. Failure of any step is null, with nothing left behind.
 */
object HlsTrailerAssembler {

    suspend fun assemble(
        httpClient: HttpClient,
        cacheDir: File,
        kind: ArtworkKind,
        masterUrl: String,
        maxBytes: Long,
    ): File? {
        val plan = HlsTrailerPlan.forKind(kind) ?: run {
            Timber.w("HLS trailer offered for $kind, which stores no video")
            return null
        }
        val parts = mutableListOf<File>()
        return try {
            val master = HlsMaster.parse(text(httpClient, masterUrl))
            val variant = master.pick(plan.maxHeight) ?: error("no rendition in $masterUrl")
            val budget = Budget(maxBytes)
            val videoUrl = HlsUrls.resolve(masterUrl, variant.uri)
            parts += track(httpClient, cacheDir, videoUrl, plan.maxSeconds, budget)
            val audioRef = master.audioUri?.takeIf { plan.withAudio }
            if (audioRef != null) {
                parts += track(httpClient, cacheDir, HlsUrls.resolve(masterUrl, audioRef), plan.maxSeconds, budget)
            }
            val out = File.createTempFile("artwork_", ".mp4", cacheDir)
            val ok = withContext(Dispatchers.IO) { remux(parts, out) } &&
                PayloadCheck.accepts(kind, ArtworkTempIO.headerOf(out))
            if (ok) {
                Timber.d("HLS trailer assembled at ${variant.height}p: ${out.length() / 1024} KB")
                out
            } else {
                out.delete()
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "HLS trailer assembly failed for $masterUrl")
            null
        } finally {
            parts.forEach { it.delete() }
        }
    }

    private class Budget(val maxBytes: Long) {
        var used = 0L
    }

    private suspend fun text(httpClient: HttpClient, url: String): String {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) throw IOException("HTTP ${response.status.value} for $url")
        return response.bodyAsText()
    }

    /** One track — init segment, then its chunks in order — appended into a single temp file. */
    private suspend fun track(
        httpClient: HttpClient,
        cacheDir: File,
        playlistUrl: String,
        maxSeconds: Double?,
        budget: Budget,
    ): File {
        val media = HlsMedia.parse(text(httpClient, playlistUrl)).within(maxSeconds)
        if (media.segments.isEmpty()) throw IOException("no chunks in $playlistUrl")
        val uris = listOfNotNull(media.initUri) + media.segments.map { it.uri }
        val file = File.createTempFile("hls_", ".m4s", cacheDir)
        try {
            file.outputStream().buffered().use { out ->
                for (uri in uris) {
                    currentCoroutineContext().ensureActive()
                    append(httpClient, HlsUrls.resolve(playlistUrl, uri), out, budget)
                }
            }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        return file
    }

    private suspend fun append(httpClient: HttpClient, url: String, out: OutputStream, budget: Budget) {
        val response = httpClient.get(url)
        if (!response.status.isSuccess()) throw IOException("HTTP ${response.status.value} for $url")
        response.bodyAsChannel().toInputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                budget.used += n
                if (budget.used > budget.maxBytes) {
                    throw IOException("trailer exceeds ${budget.maxBytes / (1024 * 1024)} MB cap")
                }
                out.write(buf, 0, n)
            }
        }
    }

    /**
     * Copies the first audio or video track of each of [inputs] into [out], interleaved by
     * presentation time so the result streams and seeks like any other mp4.
     */
    private fun remux(inputs: List<File>, out: File): Boolean {
        val extractors = mutableListOf<MediaExtractor>()
        var muxer: MediaMuxer? = null
        var started = false
        return try {
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outTracks = mutableListOf<Int>()
            var bufferSize = 1024 * 1024
            for (input in inputs) {
                val extractor = MediaExtractor().also { extractors += it }
                extractor.setDataSource(input.absolutePath)
                val index = (0 until extractor.trackCount).firstOrNull { i ->
                    val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME).orEmpty()
                    mime.startsWith("video/") || mime.startsWith("audio/")
                } ?: error("no audio or video track in ${input.name}")
                extractor.selectTrack(index)
                val format = extractor.getTrackFormat(index)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                outTracks += muxer.addTrack(format)
            }
            muxer.start()
            started = true

            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()
            val done = BooleanArray(extractors.size) { extractors[it].sampleTime < 0 }
            while (!done.all { it }) {
                // The track furthest behind writes next.
                val next = extractors.indices.filter { !done[it] }.minBy { extractors[it].sampleTime }
                val extractor = extractors[next]
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) {
                    done[next] = true
                    continue
                }
                val keyFrame = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                info.set(0, size, extractor.sampleTime, if (keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                muxer.writeSampleData(outTracks[next], buffer, info)
                if (!extractor.advance()) done[next] = true
            }
            muxer.stop()
            started = false
            out.length() > 0
        } catch (e: Exception) {
            Timber.w(e, "HLS trailer remux failed")
            false
        } finally {
            extractors.forEach { runCatching { it.release() } }
            // stop() on a muxer that never wrote throws; release() alone is always safe.
            if (started) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }
}
