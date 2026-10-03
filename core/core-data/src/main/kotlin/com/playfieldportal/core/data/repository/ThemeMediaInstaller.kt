package com.playfieldportal.core.data.repository

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import com.playfieldportal.themekit.MotionLimits
import com.playfieldportal.themekit.ThemeMediaSlots
import com.playfieldportal.themekit.ThemeMotion
import com.playfieldportal.themekit.UiMediaLimits
import com.playfieldportal.themekit.WavPcm16
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
 * The apply-side gate for media shipped inside a `.pfptheme` (plan 5.4, TS-12). A bundle is
 * untrusted input that reaches native parsers (MediaCodec / MediaMetadataRetriever) once played, so
 * nothing is installed until it has been streamed to a staging file, probed and checked against the
 * same limits the Studio export applies: [MotionLimits] for the motion wallpaper, [UiMediaLimits]
 * (with the tighter in-bundle byte caps from [ThemeMediaSlots]) for sounds, ambience, boot and
 * GameBoot.
 *
 * Rejection is per entry and never throws: a dropped entry is logged and the rest of the theme
 * still applies. A staged file is renamed into place only after it passes, so nothing unvalidated
 * ever sits under its final name.
 */
internal class ThemeMediaInstaller(private val probe: MediaProbe = ::probeWithPlatform) {

    /**
     * Streams [motion] to [dest], validates it with [MotionLimits.validate] and leaves it there.
     * Returns false (and leaves nothing behind) when it is refused.
     */
    fun installMotion(motion: ThemeMotion, dest: File): Boolean {
        val ext = dest.extension.lowercase()
        val mime = MotionLimits.mimeForExtension(ext) ?: return reject("motion", "unsupported extension .$ext")
        return stageValidateAndPlace(motion, dest, MotionLimits.MAX_BYTES, "motion") { staged ->
            val facts = probe(staged, mime) ?: return@stageValidateAndPlace MotionLimits.MSG_UNDECODABLE
            MotionLimits.validate(
                MotionLimits.Probe(
                    mime = facts.mime ?: mime,
                    width = facts.width,
                    height = facts.height,
                    durationMs = facts.durationMs ?: 0L,
                    bytes = staged.length(),
                ),
            )
        }
    }

    /**
     * Installs each entry of [media] (keyed by [ThemeMediaSlots] key) as `<dir>/<key>.<ext>`.
     * Returns the keys that were installed. [dir] is created lazily, so a theme whose every entry
     * is refused leaves no directory behind.
     */
    fun installMedia(media: Map<String, ThemeMotion>, dir: File): Set<String> {
        val installed = mutableSetOf<String>()
        for ((key, entry) in media) {
            val slot = ThemeMediaSlots.slot(key) ?: continue
            val ext = entry.extension.lowercase()
            if (!slot.accepts(ext)) {
                reject(key, "extension .$ext not accepted")
                continue
            }
            val mime = UiMediaLimits.mimeForExtension(ext)
            // The theme's own byte cap replaces the audio staging ceiling carried by the spec.
            val spec = slot.spec.copy(maxBytes = slot.maxBytes)
            dir.mkdirs()
            val normalize: (File) -> Unit = if (ext == "wav") ::pcm16InPlace else { _ -> }
            val ok = stageValidateAndPlace(entry, File(dir, "$key.$ext"), slot.maxBytes, key, normalize) { staged ->
                val facts = probe(staged, mime) ?: return@stageValidateAndPlace UiMediaLimits.MSG_UNDECODABLE
                UiMediaLimits.validate(
                    spec,
                    UiMediaLimits.Probe(
                        mime = facts.mime ?: mime,
                        durationMs = facts.durationMs,
                        bytes = staged.length(),
                    ),
                )
            }
            if (ok) installed += key
        }
        if (installed.isEmpty()) dir.delete()
        return installed
    }

    /**
     * Stream, [normalize] (rewrite the staged file in place, e.g. a float WAV as 16-bit PCM), gate,
     * then rename; every failure path removes the staging file.
     */
    private fun stageValidateAndPlace(
        source: ThemeMotion,
        dest: File,
        maxBytes: Long,
        label: String,
        normalize: (File) -> Unit = {},
        validate: (File) -> String?,
    ): Boolean {
        val staged = File(dest.parentFile, "${dest.name}.part")
        try {
            FileOutputStream(staged).use { out -> source.copyTo(CappedOutputStream(out, maxBytes)) }
            normalize(staged)
            val rejection = runCatching { validate(staged) }
                .getOrElse { Timber.w(it, "ThemeMediaInstaller: probe failed for %s", label); MotionLimits.MSG_UNDECODABLE }
            if (rejection != null) return reject(label, rejection)
            if (!staged.renameTo(dest)) {
                staged.copyTo(dest, overwrite = true)
            }
            return true
        } catch (e: Exception) {
            return reject(label, e.message ?: e.javaClass.simpleName)
        } finally {
            staged.delete()
        }
    }

    /**
     * Rewrites a staged WAV that is not plain 8/16-bit PCM (float, 24/32-bit, extensible) as 16-bit
     * PCM ([WavPcm16]) so the gate times — and SoundPool plays — the conversion. A WAV it cannot
     * read is left as staged for the gate to judge.
     */
    private fun pcm16InPlace(staged: File) {
        if (!WavPcm16.needsConversion(staged)) return
        val converted = File(staged.parentFile, "${staged.nameWithoutExtension}.pcm16.part")
        try {
            if (!WavPcm16.convert(staged, converted)) return
            if (!converted.renameTo(staged)) converted.copyTo(staged, overwrite = true)
        } finally {
            converted.delete()
        }
    }

    private fun reject(label: String, reason: String): Boolean {
        Timber.w("ThemeMediaInstaller: dropped %s - %s", label, reason)
        return false
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
