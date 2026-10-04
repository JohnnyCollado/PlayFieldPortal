package com.playfieldportal.core.data.repository

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.playfieldportal.themekit.MotionLimits
import com.playfieldportal.themekit.ThemeMediaSlots
import com.playfieldportal.themekit.ThemeMotion
import com.playfieldportal.themekit.UiMediaLimits
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import timber.log.Timber

/**
 * What a probe could read from a staged media file. [durationMs] is null when neither the platform
 * nor the container-header fallback could time it (the audio/boot gate rejects that; motion, like
 * Display's own import, treats it as zero). [width]/[height] are 0 for audio.
 */
internal data class MediaFacts(
    val mime: String?,
    val width: Int,
    val height: Int,
    val durationMs: Long?,
)

/**
 * The probe seam: `(staged file, the MIME its extension implies) -> facts`, or null when the file
 * cannot be read at all. Production is [probeWithPlatform]; MediaMetadataRetriever returns nothing
 * under Robolectric, so tests inject a fake.
 */
internal typealias MediaProbe = (File, String?) -> MediaFacts?

/**
 * The decoder seam: `(staged video) -> why this device cannot decode it`, or null when it can (or
 * the question cannot be answered — the playback fallback still covers that). Production is
 * [platformDecodeCheck]; tests inject a fake.
 */
internal typealias VideoDecodeCheck = (File) -> String?

/**
 * The apply-side gate for media shipped inside a `.pfptheme` (plan 5.4, TS-12). A bundle is
 * untrusted input that reaches native parsers (MediaCodec / MediaMetadataRetriever) once played, so
 * nothing is installed until it has been streamed to a staging file, probed and checked against the
 * same limits the Studio export applies: [MotionLimits] for the motion wallpaper, [UiMediaLimits]
 * (with the tighter in-bundle byte caps from [ThemeMediaSlots]) for sounds, ambience, boot and
 * GameBoot.
 *
 * Rejection is per entry and never throws: a dropped entry is logged and the rest of the theme
 * still applies. A staged file is renamed into place only after it passes, so nothing unvalidated
 * ever sits under its final name. Media entries pass the same [MediaGate] as a user's own pick.
 */
/** What [ThemeMediaInstaller.installMedia] did: slot keys installed, and refused keys with the reason. */
data class MediaInstallReport(val installed: Set<String>, val dropped: Map<String, String>)

internal class ThemeMediaInstaller(private val gate: MediaGate = MediaGate()) {

    /**
     * Streams [motion] to [dest], validates it with [MotionLimits.validate] and leaves it there.
     * Returns false (and leaves nothing behind) when it is refused.
     */
    fun installMotion(motion: ThemeMotion, dest: File): Boolean {
        val ext = dest.extension.lowercase()
        val mime = MotionLimits.mimeForExtension(ext) ?: run {
            reject("motion", "unsupported extension .$ext")
            return false
        }
        return stageValidateAndPlace(motion, dest, MotionLimits.MAX_BYTES, "motion") { staged ->
            val facts = gate.probe(staged, mime) ?: return@stageValidateAndPlace MotionLimits.MSG_UNDECODABLE
            MotionLimits.validate(
                MotionLimits.Probe(
                    mime = facts.mime ?: mime,
                    width = facts.width,
                    height = facts.height,
                    durationMs = facts.durationMs ?: 0L,
                    bytes = staged.length(),
                ),
            )
        } == null
    }

    /**
     * Installs each entry of [media] (keyed by [ThemeMediaSlots] key) as `<dir>/<key>.<ext>`.
     * Reports what was installed and, for every refused entry, the gate's own reason — the caller
     * surfaces those, so a theme never loses a clip silently. [dir] is created lazily, so a theme
     * whose every entry is refused leaves no directory behind.
     */
    fun installMedia(media: Map<String, ThemeMotion>, dir: File): MediaInstallReport {
        val installed = mutableSetOf<String>()
        val dropped = linkedMapOf<String, String>()
        for ((key, entry) in media) {
            val slot = ThemeMediaSlots.slot(key) ?: continue
            val ext = entry.extension.lowercase()
            if (!slot.accepts(ext)) {
                dropped[key] = reject(
                    key,
                    if (slot.kind == UiMediaLimits.Kind.VIDEO) UiMediaLimits.MSG_UNSUPPORTED_FORMAT_VIDEO
                    else UiMediaLimits.MSG_UNSUPPORTED_FORMAT_AUDIO,
                )
                continue
            }
            // The theme's own byte cap replaces the audio staging ceiling carried by the spec.
            val spec = slot.spec.copy(maxBytes = slot.maxBytes)
            dir.mkdirs()
            val rejection = stageValidateAndPlace(entry, File(dir, "$key.$ext"), slot.maxBytes, key) { staged ->
                gate.check(staged, ext, spec)
            }
            if (rejection == null) installed += key else dropped[key] = rejection
        }
        if (installed.isEmpty()) dir.delete()
        return MediaInstallReport(installed, dropped)
    }

    /**
     * Stream, gate (which may rewrite the staged file in place, e.g. a float WAV as 16-bit PCM),
     * then rename; every failure path removes the staging file. Null when placed, else the reason.
     */
    private fun stageValidateAndPlace(
        source: ThemeMotion,
        dest: File,
        maxBytes: Long,
        label: String,
        validate: (File) -> String?,
    ): String? {
        val staged = File(dest.parentFile, "${dest.name}.part")
        try {
            FileOutputStream(staged).use { out -> source.copyTo(CappedOutputStream(out, maxBytes)) }
            val rejection = runCatching { validate(staged) }
                .getOrElse { Timber.w(it, "ThemeMediaInstaller: probe failed for %s", label); MotionLimits.MSG_UNDECODABLE }
            if (rejection != null) return reject(label, rejection)
            if (!staged.renameTo(dest)) {
                staged.copyTo(dest, overwrite = true)
            }
            return null
        } catch (e: Exception) {
            return reject(label, e.message ?: e.javaClass.simpleName)
        } finally {
            staged.delete()
        }
    }

    /** Logs a refused entry and hands its reason back. */
    private fun reject(label: String, reason: String): String {
        Timber.w("ThemeMediaInstaller: dropped %s - %s", label, reason)
        return reason
    }

    /** Fails the stream once more than [cap] bytes arrive, so a crafted entry cannot fill the disk. */
    private class CappedOutputStream(private val out: OutputStream, private val cap: Long) : OutputStream() {
        private var written = 0L

        private fun account(n: Int) {
            written += n
            if (written > cap) throw IOException("entry exceeds the $cap-byte cap")
        }

        override fun write(b: Int) {
            account(1)
            out.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            account(len)
            out.write(b, off, len)
        }

        override fun flush() = out.flush()
    }
}

/**
 * Production probe. Animated images get BitmapFactory bounds (a duration is unknowable cheaply and
 * Display's motion import does the same); everything else goes through MediaMetadataRetriever, with
 * [MediaDurationFallback]'s container-header math when the platform cannot time the file.
 */
internal fun probeWithPlatform(file: File, expectedMime: String?): MediaFacts? = runCatching {
    if (expectedMime == "image/gif" || expectedMime == "image/webp") {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        MediaFacts(expectedMime, bounds.outWidth, bounds.outHeight, 0L)
    } else {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(file.absolutePath)
            val mime = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE) ?: expectedMime
            MediaFacts(
                mime = mime,
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0,
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0,
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?: MediaDurationFallback.durationMs(file, mime),
            )
        }
    }
}.getOrNull()

/**
 * Asks this device's codecs whether any decoder takes [file]'s video track, profile included —
 * the check that catches an H.264 High 4:4:4 clip, which every container probe happily times.
 * Null when it can be decoded, or when the question cannot be answered here.
 */
internal fun platformDecodeCheck(file: File): String? {
    val extractor = android.media.MediaExtractor()
    return try {
        extractor.setDataSource(file.absolutePath)
        val format = (0 until extractor.trackCount)
            .map(extractor::getTrackFormat)
            .firstOrNull { it.getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            ?: return null
        // A frame rate in the query makes some releases refuse a format they decode fine.
        format.removeKey(android.media.MediaFormat.KEY_FRAME_RATE)
        val decoder = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format)
        if (decoder != null) null
        else "This device can't play this video (${describeVideo(format)}) — re-export the theme from the Theme Studio, which converts it"
    } catch (e: Exception) {
        Timber.w(e, "ThemeMediaInstaller: could not check %s against the decoders", file.name)
        null
    } finally {
        extractor.release()
    }
}

/** "H.264 High 4:4:4" for an AVC track; the MIME type and profile number for anything else. */
private fun describeVideo(format: android.media.MediaFormat): String {
    val mime = format.getString(android.media.MediaFormat.KEY_MIME).orEmpty()
    val profile = if (format.containsKey(android.media.MediaFormat.KEY_PROFILE)) format.getInteger(android.media.MediaFormat.KEY_PROFILE) else null
    if (mime != android.media.MediaFormat.MIMETYPE_VIDEO_AVC) return listOfNotNull(mime, profile?.let { "profile $it" }).joinToString(", ")
    val name = when (profile) {
        android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh444 -> "High 4:4:4"
        android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh422 -> "High 4:2:2"
        android.media.MediaCodecInfo.CodecProfileLevel.AVCProfileHigh10 -> "High 10"
        null -> null
        else -> "profile $profile"
    }
    return listOfNotNull("H.264", name).joinToString(" ")
}
