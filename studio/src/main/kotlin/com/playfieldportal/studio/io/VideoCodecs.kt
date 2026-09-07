package com.playfieldportal.studio.io

import com.playfieldportal.themekit.MotionLimits
import java.awt.image.BufferedImage
import java.io.File
import org.jcodec.api.FrameGrab
import org.jcodec.common.io.NIOUtils
import org.jcodec.scale.AWTUtil

/**
 * Accepts a user-picked video as a motion wallpaper: validates it, and lifts its first frame out
 * as the still poster.
 *
 * ## Why the video is not converted
 *
 * An earlier design transcoded the pick to GIF "for performance". It is worth recording that this
 * was backwards, because the instinct is a common one. On device, [com.playfieldportal.core.ui.motion.formatOf]
 * routes `.mp4` to ExoPlayer — a hardware decoder — and `.gif` to Coil's `MovieDrawable`, which
 * decodes on the CPU and holds every frame as a bitmap. For the same clip the MP4 is roughly a
 * tenth the size AND cheaper per frame. Transcoding would have spent real effort to produce the
 * worse artifact. The video is passed through untouched, and the launcher plays it the fast way.
 *
 * ## What passing through costs, stated plainly
 *
 * The user's original container bytes ship inside the theme and are parsed on someone else's
 * device by MediaCodec — a native decoder with a long CVE history. Re-encoding from pixels would
 * have severed that, and it is the one genuine advantage the transcode had.
 *
 * It is not a new hole. `DisplaySettingsViewModel` already lets anyone import an arbitrary MP4 as
 * a motion wallpaper, and themes already carry attacker-controlled bytes as wallpapers and icons.
 * This uses the existing exposure rather than widening it. What it does mean is that the checks
 * below are not a formality: `PfpThemeStore.apply()` installs a bundle's motion entry without
 * re-running [MotionLimits.validate], so for a Studio-authored theme **this is the only gate
 * there is**, and a bad pick must fail here on the desktop rather than on someone's handheld.
 *
 * ## Why JCodec is still here
 *
 * Only for the poster, and only ever for one frame. The launcher enforces "motion is never set
 * without its poster" ([com.playfieldportal.core.ui.motion.MotionWallpaperPolicy] renders the
 * still whenever no decoder may exist), and `PfpThemeStore.apply()` maps the bundle's *still
 * wallpaper* onto that poster key — so a bundle carrying motion and no still is an invalid theme.
 * When the user imports only a video, frame 1 becomes the still. Pure Java, MP4/H.264 only; a
 * hostile stream gets an exception rather than the native overflow a C decoder would risk.
 */
object VideoCodecs {

    /**
     * What JCodec can read well enough to lift a poster from. Narrower than
     * [MotionLimits.SUPPORTED_MIME] on purpose — WebM is a fine motion wallpaper for the launcher
     * but JCodec cannot demux it, so the Studio cannot produce the poster the theme requires and
     * cannot read the dimensions [MotionLimits.validate] needs. Accepting it would mean shipping
     * an unvalidated, poster-less video; rejecting it names the reason instead.
     */
    val SUPPORTED_EXTENSIONS = setOf("mp4", "m4v")

    /**
     * Byte cap applied before the file is opened. The pick IS the artifact now, so the artifact's
     * own cap governs — there is no looser "source" budget to justify, and a file over this can
     * never produce a valid theme no matter what else is true of it.
     */
    const val MAX_SOURCE_BYTES = MotionLimits.MAX_BYTES

    const val MSG_UNSUPPORTED_SOURCE = "Unsupported video — use an MP4 (H.264)"

    /** The result of offering a video to the Studio. */
    sealed interface Outcome {
        /**
         * [poster] is frame 1 at the video's own resolution, to be pushed through the ordinary
         * still-wallpaper import (which crops it, derives the accent, and runs the legibility
         * check). [bundleExtension] is what the motion entry must be named inside the zip —
         * `PfpThemeCodec` accepts only mp4/webm/gif and drops anything else without a word.
         */
        data class Accepted(
            val poster: BufferedImage,
            val probe: MotionLimits.Probe,
            val bundleExtension: String,
        ) : Outcome

        /** [message] is a [MotionLimits] string, shown to the user verbatim. */
        data class Rejected(val message: String) : Outcome
    }

    /**
     * Reads the container header only — no frame is decoded — so a four-hour movie is rejected in
     * milliseconds rather than after gigabytes of work. Null when JCodec cannot parse the file as
     * MP4/H.264 at all, which is also the answer for a file that merely claims to be one.
     */
    fun probe(file: File): MotionLimits.Probe? = runCatching {
        NIOUtils.readableChannel(file).use { channel ->
            val meta = FrameGrab.createFrameGrab(channel).videoTrack.meta
            val size = meta.videoCodecMeta.size
            MotionLimits.Probe(
                mime = MotionLimits.mimeForExtension(file.extension),
                width = size.width,
                height = size.height,
                durationMs = (meta.totalDuration * 1000.0).toLong(),
                bytes = file.length(),
            )
        }
    }.getOrNull()

    /** Frame 1 as an image, or null when the file yields no decodable frame. */
    fun firstFrame(file: File): BufferedImage? = runCatching {
        NIOUtils.readableChannel(file).use { channel ->
            FrameGrab.createFrameGrab(channel).nativeFrame?.let(AWTUtil::toBufferedImage)
        }
    }.getOrNull()

    /**
     * The whole import gate, cheapest check first: extension, then length, then the header, then
     * [MotionLimits.validate], and only once all of those pass is a single frame decoded.
     *
     * Ordering is not just UX here — each step is what makes the next one safe to attempt. The
     * length check runs before the file is opened; the header check runs before any frame is.
     */
    fun accept(file: File): Outcome {
        val extension = file.extension.lowercase()
        if (extension !in SUPPORTED_EXTENSIONS) return Outcome.Rejected(MSG_UNSUPPORTED_SOURCE)
        if (!file.isFile) return Outcome.Rejected(MotionLimits.MSG_UNDECODABLE)
        if (file.length() > MAX_SOURCE_BYTES) return Outcome.Rejected(MotionLimits.MSG_TOO_LARGE_BYTES)

        val probe = probe(file) ?: return Outcome.Rejected(MotionLimits.MSG_UNDECODABLE)
        MotionLimits.validate(probe)?.let { return Outcome.Rejected(it) }

        val poster = firstFrame(file) ?: return Outcome.Rejected(MotionLimits.MSG_UNDECODABLE)
        // Non-null by construction: SUPPORTED_EXTENSIONS is a subset of what the mapping covers.
        val bundleExtension = MotionLimits.bundleExtensionFor(extension)
            ?: return Outcome.Rejected(MSG_UNSUPPORTED_SOURCE)

        return Outcome.Accepted(poster = poster, probe = probe, bundleExtension = bundleExtension)
    }
}
