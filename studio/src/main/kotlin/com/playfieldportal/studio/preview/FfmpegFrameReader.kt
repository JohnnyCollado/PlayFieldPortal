package com.playfieldportal.studio.preview

import com.playfieldportal.themekit.MotionCrop
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToLong
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameGrabber

/** One preview-sized BGRA picture and when it shows. Reused: the reader fills it, [FrameBitmaps] copies it out. */
internal class BgraFrame(val width: Int, val height: Int) {
    val bytes = ByteArray(width * height * 4)
    var ptsMs = 0L
    var durationMs = 0L
}

/**
 * Sequential playback of a clip through FFmpeg, every frame already framed for the preview: decoded
 * on all cores (the codec's own frame threads), converted to BGRA, then cropped to exactly the
 * region the launcher's `motionTransform` shows ([MotionFraming.sourceRect]) and scaled to
 * [outWidth] x [outHeight] by FFmpeg's filter graph. Nothing is done per pixel in Java.
 *
 * Playback only. Files reach it after the JCodec import gate ([com.playfieldportal.studio.io.VideoCodecs])
 * has probed them. Not thread-safe — one owner.
 */
internal class FfmpegFrameReader private constructor(
    private val grabber: FFmpegFrameGrabber,
    private val filter: FFmpegFrameFilter,
    val fps: Float,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val outWidth: Int,
    val outHeight: Int,
) : AutoCloseable {

    private val frameMs = (1000f / fps).roundToLong().coerceAtLeast(1L)

    /** Decodes the next frame into [into]; false at the end of the stream. */
    fun next(into: BgraFrame): Boolean {
        while (true) {
            val decoded = grabber.grabImage() ?: return false
            val ptsUs = decoded.timestamp
            filter.push(decoded, avutil.AV_PIX_FMT_BGRA)
            // Crop and scale are one-in, one-out; a null pull would only be a filter still priming.
            val out = filter.pull() ?: continue
            copyRows(out.image[0] as ByteBuffer, out.imageStride, into)
            into.ptsMs = ptsUs / 1000L
            into.durationMs = frameMs
            return true
        }
    }

    /**
     * Back to the first frame. Reopens rather than seeks: JavaCV's seek settles on whichever stream's
     * packet comes first, so a clip with an audio track can resume a frame late, and a seamless loop
     * shows that skip every pass. Reopening costs milliseconds once a pass, which the read-ahead absorbs.
     */
    fun rewind() {
        grabber.restart()
    }

    override fun close() {
        runCatching { filter.close() }
        runCatching { grabber.close() }
    }

    private fun copyRows(src: ByteBuffer, stride: Int, into: BgraFrame) {
        val rowBytes = outWidth * 4
        val rows = src.duplicate()
        for (y in 0 until outHeight) {
            rows.position(y * stride)
            rows.get(into.bytes, y * rowBytes, rowBytes)
        }
    }

    companion object {
        /**
         * Opens [file] framed by [crop] into [outW] x [outH] frames, or null when FFmpeg cannot read
         * it as video.
         */
        fun open(file: File, crop: MotionCrop?, outW: Int, outH: Int): FfmpegFrameReader? {
            if (!file.isFile) return null
            // FFmpeg prints every container quirk and unreadable file to stderr; the preview only
            // cares whether it decodes, and says so itself when it cannot.
            avutil.av_log_set_level(avutil.AV_LOG_FATAL)
            val grabber = FFmpegFrameGrabber(file)
            var filter: FFmpegFrameFilter? = null
            return try {
                grabber.pixelFormat = avutil.AV_PIX_FMT_BGRA
                // 0 = one decode thread per core.
                grabber.setVideoOption("threads", "0")
                grabber.start()
                val w = grabber.imageWidth
                val h = grabber.imageHeight
                require(w > 0 && h > 0 && grabber.hasVideo()) { "no video stream" }
                val r = MotionFraming.sourceRect(w, h, outW, outH, crop)
                filter = FFmpegFrameFilter(
                    "crop=${r.right - r.left}:${r.bottom - r.top}:${r.left}:${r.top}," +
                        "scale=$outW:$outH:flags=bilinear,format=bgra",
                    w, h,
                ).apply {
                    pixelFormat = avutil.AV_PIX_FMT_BGRA
                    frameRate = grabber.frameRate
                    start()
                }
                val fps = grabber.videoFrameRate.toFloat().takeIf { it.isFinite() && it > 0f } ?: DEFAULT_FPS
                FfmpegFrameReader(grabber, filter, fps.coerceIn(1f, 240f), w, h, outW, outH)
            } catch (e: Exception) {
                runCatching { filter?.close() }
                runCatching { grabber.close() }
                null
            }
        }

        private const val DEFAULT_FPS = 30f
    }
}
