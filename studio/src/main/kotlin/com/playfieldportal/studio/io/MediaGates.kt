package com.playfieldportal.studio.io

import com.playfieldportal.themekit.MediaDurationProbe
import com.playfieldportal.themekit.ThemeMediaSlots
import com.playfieldportal.themekit.UiMediaLimits
import com.playfieldportal.themekit.WavPcm16
import java.io.File

/**
 * The Studio's import gate for the v4 UI-media slots (menu sounds, ambience, boot, GameBoot): it
 * rejects, by name, anything the launcher's own gate would reject, so a bad pick fails on the
 * desktop rather than on someone's handheld.
 *
 * It is [UiMediaLimits.validate] fed by pure-JVM probes - the very function the launcher runs, so
 * the two cannot drift. Audio is timed by [MediaDurationProbe] (MP3 / WAV / OGG / M4A headers);
 * video by the existing JCodec header probe ([VideoCodecs.probe]). No audio decoder is added
 * (plan A10), which is also why WebM cannot be authored: JCodec cannot demux it, so there is no
 * duration to validate.
 *
 * Cheapest check first: slot, file, extension, byte cap, and only then a header read. Two steps
 * go beyond checking, and in both the conversion is what gets checked and staged: a WAV that is not
 * plain 8/16-bit PCM (float, 24/32-bit, extensible) becomes 16-bit PCM ([WavPcm16]), and a video
 * Android cannot be relied on to decode (H.264 High 4:4:4 and the like) is re-encoded to H.264
 * 4:2:0 ([AndroidVideo]).
 */
object MediaGates {

    /** Plan A10: the one place the Studio is narrower than the launcher, and it says why. */
    const val MSG_WEBM_NOT_AUTHORABLE =
        "WebM clips can't be checked in the Studio (it reads MP4/H.264 only) — convert the clip to MP4"

    const val MSG_UNKNOWN_SLOT = "That isn't a media slot this import can fill"

    sealed interface Outcome {
        /**
         * [extension] is the normalised bundle extension (`m4v` -> `mp4`, `oga` -> `ogg`). [source] is
         * the file to stage: the pick itself, or — for a WAV that is not plain 8/16-bit PCM — its
         * 16-bit PCM conversion ([WavPcm16]), a temporary file the caller deletes once staged.
         */
        data class Accepted(
            val extension: String,
            val probe: UiMediaLimits.Probe,
            val source: File,
            /** Set when [source] is a re-encode made for Android: why the pick needed one. */
            val note: String? = null,
        ) : Outcome
        data class Rejected(val message: String) : Outcome
    }

    /** Float audio is at most 4x the bytes of its 16-bit conversion (64-bit float -> 16-bit). */
    private const val MAX_CONVERSION_SHRINK = 4

    /** How much an Android re-encode may shrink a clip and still be worth attempting. */
    private const val MAX_VIDEO_SHRINK = 4

    /** The theme-side byte-cap rejection (A9) - audio only; video reuses [UiMediaLimits.tooLarge]. */
    fun tooLargeForTheme(slot: ThemeMediaSlots.Slot): String =
        "That file is too large for a theme — ${slot.maxBytes / (1024 * 1024)} MB or less"

    /**
     * Gates [file] for [slotKey]. A WAV that is not plain 8/16-bit PCM is first converted to 16-bit
     * PCM in [workDir] (the system temp directory by default) and every check after that runs on the
     * conversion — its length, its bytes — since that is what the theme will carry. A conversion that
     * is then rejected is deleted.
     */
    fun check(slotKey: String, file: File, workDir: File? = null): Outcome {
        val slot = ThemeMediaSlots.slot(slotKey) ?: return Outcome.Rejected(MSG_UNKNOWN_SLOT)
        val video = slot.kind == UiMediaLimits.Kind.VIDEO
        if (!file.isFile) return Outcome.Rejected(UiMediaLimits.MSG_UNDECODABLE)

        val extension = file.extension.lowercase()
        if (video && extension == "webm") return Outcome.Rejected(MSG_WEBM_NOT_AUTHORABLE)
        val mime = UiMediaLimits.mimeForExtension(extension)
        val unsupported =
            if (video) UiMediaLimits.MSG_UNSUPPORTED_FORMAT_VIDEO else UiMediaLimits.MSG_UNSUPPORTED_FORMAT_AUDIO
        if (mime == null) return Outcome.Rejected(unsupported)

        val converted = if (!video && extension == "wav") convertedWav(file, slot, workDir) else null
        // A clip far past the cap is not worth re-encoding to find out it still does not fit.
        if (video && file.length() > slot.maxBytes * MAX_VIDEO_SHRINK) return Outcome.Rejected(UiMediaLimits.tooLarge(slot.spec))
        val playable = if (video) AndroidVideo.playable(file, workDir) else null
        if (playable is AndroidVideo.Playable.Failed) return Outcome.Rejected(playable.message)
        val reencoded = (playable as? AndroidVideo.Playable.Converted)
        val outcome = checkContent(slot, converted ?: reencoded?.file ?: file, mime, unsupported)
        if (outcome is Outcome.Rejected) {
            converted?.delete()
            reencoded?.file?.delete()
            return outcome
        }
        return if (reencoded != null && outcome is Outcome.Accepted) outcome.copy(note = reencoded.reason) else outcome
    }

    /** [file]'s 16-bit PCM conversion when it needs one (and is small enough to be worth reading). */
    private fun convertedWav(file: File, slot: ThemeMediaSlots.Slot, workDir: File?): File? {
        if (file.length() > slot.maxBytes * MAX_CONVERSION_SHRINK || !WavPcm16.needsConversion(file)) return null
        val out = File.createTempFile("studio-wav-", ".wav", workDir)
        return out.takeIf { WavPcm16.convert(file, it) } ?: run {
            out.delete()
            null
        }
    }

    private fun checkContent(slot: ThemeMediaSlots.Slot, file: File, mime: String, unsupported: String): Outcome {
        val video = slot.kind == UiMediaLimits.Kind.VIDEO
        val bytes = file.length()
        if (bytes > slot.maxBytes) {
            return Outcome.Rejected(if (video) UiMediaLimits.tooLarge(slot.spec) else tooLargeForTheme(slot))
        }

        val allowed = if (video) UiMediaLimits.VIDEO_MIME else UiMediaLimits.AUDIO_MIME
        val durationMs: Long? = when {
            mime !in allowed -> null // validate() reports the format; no point opening the file
            video -> VideoCodecs.probe(file)?.durationMs
                ?: return Outcome.Rejected(UiMediaLimits.MSG_UNDECODABLE)
            else -> MediaDurationProbe.durationMs(file, mime)
        }
        val probe = UiMediaLimits.Probe(mime, durationMs, bytes)
        UiMediaLimits.validate(slot.spec, probe)?.let { return Outcome.Rejected(it) }

        val stored = UiMediaLimits.extensionForMime(mime)
            ?.takeIf(slot::accepts)
            ?: return Outcome.Rejected(unsupported)
        return Outcome.Accepted(stored, probe, file)
    }
}
