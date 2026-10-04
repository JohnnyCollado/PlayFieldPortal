package com.playfieldportal.studio.io

import java.io.File
import java.util.concurrent.TimeUnit
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacpp.Loader
import org.bytedeco.javacv.FFmpegFrameGrabber

/**
 * Makes a clip playable on Android before it goes into a theme. Every device decodes H.264 in
 * 4:2:0 at the Baseline / Main / High profiles; anything else — High 4:4:4 / 4:2:2 / High 10,
 * 4:4:4 or 10-bit pixels, MPEG-4 Part 2, HEVC — may not decode on a given handheld, and when a
 * boot or GameBoot clip fails there the launcher shows its built-in sequence instead. So the Studio
 * re-encodes such a clip on import with the bundled FFmpeg (OpenH264, 4:2:0, the same size and
 * frame rate, the audio copied), and leaves an ordinary H.264 clip exactly as it is.
 */
object AndroidVideo {

    /** What a clip's first video stream is: FFmpeg's codec name, H.264 profile, and pixel format. */
    data class Format(
        val codec: String,
        val profile: Int?,
        val pixelFormat: String?,
        val durationMs: Long = 0,
        val bitRate: Long = 0,
    )

    sealed interface Playable {
        /** Already plays everywhere: stage the pick itself. */
        data object AsIs : Playable

        /** A playable re-encode in [file] (a temp file the caller deletes once staged); [reason] says why. */
        data class Converted(val file: File, val reason: String) : Playable

        /** It needed converting and the conversion failed; [message] is for the author. */
        data class Failed(val message: String) : Playable
    }

    // FF_PROFILE_H264_*: Baseline, Constrained Baseline, Main, High.
    private val PLAYABLE_H264_PROFILES = setOf(66, 578, 77, 100)
    private val PLAYABLE_PIXEL_FORMATS = setOf("yuv420p", "yuvj420p")

    /** Why [format] must be re-encoded for Android, or null when it plays as it is. */
    fun reasonToConvert(format: Format): String? = when {
        format.codec != "h264" -> "${codecLabel(format.codec)} video isn't guaranteed to play on Android"
        format.profile != null && format.profile !in PLAYABLE_H264_PROFILES ->
            "H.264 ${profileLabel(format.profile)} doesn't play on Android"
        format.pixelFormat != null && format.pixelFormat !in PLAYABLE_PIXEL_FORMATS ->
            "${format.pixelFormat} video doesn't play on Android"
        else -> null
    }

    /** The bundled `ffmpeg` program (extracted from the JavaCPP natives for this OS). */
    fun ffmpegPath(): String = Loader.load(org.bytedeco.ffmpeg.ffmpeg::class.java)

    /** The first video stream's format, or null when FFmpeg cannot open [file] as video. */
    fun probe(file: File): Format? {
        if (!file.isFile) return null
        avutil.av_log_set_level(avutil.AV_LOG_FATAL)
        val grabber = FFmpegFrameGrabber(file)
        return try {
            grabber.start()
            val context = grabber.formatContext
            val stream = (0 until context.nb_streams())
                .map { context.streams(it) }
                .firstOrNull { it.codecpar().codec_type() == avutil.AVMEDIA_TYPE_VIDEO }
                ?: return null
            val par = stream.codecpar()
            Format(
                codec = avcodec.avcodec_get_name(par.codec_id()).string,
                profile = par.profile().takeIf { it >= 0 },
                pixelFormat = avutil.av_get_pix_fmt_name(par.format())?.string,
                durationMs = grabber.lengthInTime / 1000,
                bitRate = par.bit_rate().takeIf { it > 0 } ?: grabber.videoBitrate.toLong(),
            )
        } catch (e: Exception) {
            null
        } finally {
            runCatching { grabber.close() }
        }
    }

    /**
     * [file] made playable: [Playable.AsIs] when it already is (or FFmpeg cannot read it at all —
     * the import gate then rejects it with its own reason), else a conversion in [workDir].
     */
    fun playable(file: File, workDir: File? = null): Playable {
        val format = probe(file) ?: return Playable.AsIs
        val reason = reasonToConvert(format) ?: return Playable.AsIs
        val out = File.createTempFile("studio-video-", ".mp4", workDir)
        val converted = convert(file, out, targetBitRate(format)) &&
            probe(out)?.let { reasonToConvert(it) == null } == true
        if (!converted) {
            out.delete()
            return Playable.Failed("Couldn't convert this video for Android ($reason). Re-encode it as MP4 H.264 and try again.")
        }
        return Playable.Converted(out, reason)
    }

    /** The source's own video rate, kept within a range that stays clean at 1080p and inside the caps. */
    private fun targetBitRate(format: Format): Long =
        (format.bitRate.takeIf { it > 0 } ?: DEFAULT_BIT_RATE).coerceIn(MIN_BIT_RATE, MAX_BIT_RATE)

    /** Re-encodes the video to H.264 4:2:0, copying the audio (AAC when it cannot be copied). */
    private fun convert(src: File, dest: File, bitRate: Long): Boolean =
        run(command(src, dest, bitRate, copyAudio = true)) || run(command(src, dest, bitRate, copyAudio = false))

    private fun command(src: File, dest: File, bitRate: Long, copyAudio: Boolean): List<String> = listOf(
        ffmpegPath(), "-y", "-v", "error",
        "-i", src.absolutePath,
        "-map", "0:v:0", "-map", "0:a:0?",
        // 4:2:0 needs even dimensions; an odd edge loses one pixel.
        "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2",
        "-c:v", "libopenh264", "-pix_fmt", "yuv420p", "-allow_skip_frames", "0",
        "-b:v", "$bitRate", "-maxrate", "${bitRate * 5 / 4}",
    ) + (if (copyAudio) listOf("-c:a", "copy") else listOf("-c:a", "aac", "-b:a", "160k")) +
        listOf("-movflags", "+faststart", dest.absolutePath)

    private fun run(command: List<String>): Boolean = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText() // drain, or a chatty run can block on a full pipe
        process.waitFor(CONVERT_TIMEOUT_MIN, TimeUnit.MINUTES) && process.exitValue() == 0
    }.getOrDefault(false)

    private fun codecLabel(codec: String): String = when (codec) {
        "mpeg4" -> "MPEG-4 Part 2"
        "hevc" -> "HEVC (H.265)"
        "vp9" -> "VP9"
        "av1" -> "AV1"
        else -> codec.uppercase()
    }

    private fun profileLabel(profile: Int): String = when (profile) {
        244 -> "High 4:4:4 Predictive"
        122 -> "High 4:2:2"
        110 -> "High 10"
        88 -> "Extended"
        44 -> "CAVLC 4:4:4"
        else -> "profile $profile"
    }

    private const val DEFAULT_BIT_RATE = 8_000_000L
    private const val MIN_BIT_RATE = 2_000_000L
    private const val MAX_BIT_RATE = 10_000_000L
    private const val CONVERT_TIMEOUT_MIN = 5L
}
