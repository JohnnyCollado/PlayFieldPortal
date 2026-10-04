package com.playfieldportal.studio.io

import java.io.File
import java.nio.ShortBuffer
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber

/**
 * Sequential decoding of a theme sound (MP3, OGG, M4A or WAV) through FFmpeg, as interleaved
 * little-endian 16-bit PCM at the file's own rate. Anything over two channels is mixed down to
 * stereo so every desktop audio line can take it.
 *
 * Playback only, for [SoundPlayer]. Not thread-safe — one owner.
 */
internal class FfmpegAudioReader private constructor(
    private val grabber: FFmpegFrameGrabber,
    val sampleRate: Int,
    val channels: Int,
) : AutoCloseable {

    /** The next decoded chunk, or null at the end of the stream. */
    fun read(): ByteArray? {
        while (true) {
            val frame = grabber.grabSamples() ?: return null
            val samples = frame.samples?.firstOrNull() as? ShortBuffer ?: continue
            val src = samples.duplicate()
            if (!src.hasRemaining()) continue
            val out = ByteArray(src.remaining() * 2)
            var i = 0
            while (src.hasRemaining()) {
                val s = src.get().toInt()
                out[i++] = s.toByte()
                out[i++] = (s shr 8).toByte()
            }
            return out
        }
    }

    /** Back to the start of the clip. */
    fun rewind() {
        grabber.restart()
    }

    override fun close() {
        runCatching { grabber.close() }
    }

    companion object {
        /** Opens [file], or null when FFmpeg finds no audio it can decode in it. */
        fun open(file: File): FfmpegAudioReader? {
            if (!file.isFile) return null
            avutil.av_log_set_level(avutil.AV_LOG_FATAL)
            var grabber = FFmpegFrameGrabber(file)
            return try {
                grabber.sampleFormat = avutil.AV_SAMPLE_FMT_S16
                grabber.start()
                require(grabber.hasAudio() && grabber.sampleRate > 0 && grabber.audioChannels > 0) { "no audio stream" }
                if (grabber.audioChannels > 2) {
                    grabber.close()
                    grabber = FFmpegFrameGrabber(file).apply {
                        sampleFormat = avutil.AV_SAMPLE_FMT_S16
                        audioChannels = 2
                        start()
                    }
                }
                FfmpegAudioReader(grabber, grabber.sampleRate, grabber.audioChannels)
            } catch (e: Exception) {
                runCatching { grabber.close() }
                null
            }
        }
    }
}
