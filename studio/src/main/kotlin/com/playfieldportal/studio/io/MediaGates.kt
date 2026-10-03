package com.playfieldportal.studio.io

import com.playfieldportal.themekit.MediaDurationProbe
import com.playfieldportal.themekit.ThemeMediaSlots
import com.playfieldportal.themekit.UiMediaLimits
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
 * Cheapest check first: slot, file, extension, byte cap, and only then a header read.
 */
object MediaGates {

    /** Plan A10: the one place the Studio is narrower than the launcher, and it says why. */
    const val MSG_WEBM_NOT_AUTHORABLE =
        "WebM clips can't be checked in the Studio (it reads MP4/H.264 only) — convert the clip to MP4"

    const val MSG_UNKNOWN_SLOT = "That isn't a media slot this import can fill"

    sealed interface Outcome {
        /** [extension] is the normalised bundle extension (`m4v` -> `mp4`, `oga` -> `ogg`). */
        data class Accepted(val extension: String, val probe: UiMediaLimits.Probe) : Outcome
        data class Rejected(val message: String) : Outcome
    }

    /** The theme-side byte-cap rejection (A9) - audio only; video reuses [UiMediaLimits.tooLarge]. */
    fun tooLargeForTheme(slot: ThemeMediaSlots.Slot): String =
        "That file is too large for a theme — ${slot.maxBytes / (1024 * 1024)} MB or less"

    fun check(slotKey: String, file: File): Outcome {
        val slot = ThemeMediaSlots.slot(slotKey) ?: return Outcome.Rejected(MSG_UNKNOWN_SLOT)
        val video = slot.kind == UiMediaLimits.Kind.VIDEO
        if (!file.isFile) return Outcome.Rejected(UiMediaLimits.MSG_UNDECODABLE)

        val extension = file.extension.lowercase()
        if (video && extension == "webm") return Outcome.Rejected(MSG_WEBM_NOT_AUTHORABLE)
        val mime = UiMediaLimits.mimeForExtension(extension)
        val unsupported =
            if (video) UiMediaLimits.MSG_UNSUPPORTED_FORMAT_VIDEO else UiMediaLimits.MSG_UNSUPPORTED_FORMAT_AUDIO
        if (mime == null) return Outcome.Rejected(unsupported)

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
        return Outcome.Accepted(stored, probe)
    }
}
