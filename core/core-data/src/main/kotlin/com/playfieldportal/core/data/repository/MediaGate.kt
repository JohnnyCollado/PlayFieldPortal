package com.playfieldportal.core.data.repository

import com.playfieldportal.themekit.UiMediaLimits
import com.playfieldportal.themekit.WavPcm16
import java.io.File
import timber.log.Timber

/**
 * The one gate every UI-media file passes before it is placed, whoever supplies it: a user's own
 * pick ([UiMediaStore.import]) and a theme's entry ([ThemeMediaInstaller]). Both used to run their
 * own copy of this pipeline, and only the theme's asked the decoders — so a user's own H.264
 * High 4:4:4 boot clip installed and then fell back to the built-in presentation at every launch.
 *
 * In order: normalize (a WAV that is not plain 8/16-bit PCM is rewritten as 16-bit PCM in place, so
 * the gate times — and SoundPool plays — the conversion), probe, hold to the slot's
 * [UiMediaLimits.Spec], and for a video slot ask whether any decoder on this device takes it.
 *
 * The probe and the decoder are seams with two adapters: the platform ones in production, fakes in
 * tests (MediaMetadataRetriever and MediaCodecList answer nothing useful under Robolectric).
 */
internal class MediaGate(
    val probe: MediaProbe = ::probeWithPlatform,
    private val decodeCheck: VideoDecodeCheck = ::platformDecodeCheck,
) {

    /**
     * Checks [staged] — a staging file whose container is [extension] — against [spec]. Null when it
     * passes, else the reason in the words Settings shows. The file may be rewritten in place (WAV
     * normalization); it is never moved or deleted here.
     */
    fun check(staged: File, extension: String, spec: UiMediaLimits.Spec): String? = runCatching {
        val ext = extension.lowercase()
        if (ext == "wav") pcm16InPlace(staged)
        val mime = UiMediaLimits.mimeForExtension(ext)
        val facts = probe(staged, mime) ?: return@runCatching UiMediaLimits.MSG_UNDECODABLE
        UiMediaLimits.validate(
            spec,
            UiMediaLimits.Probe(mime = facts.mime ?: mime, durationMs = facts.durationMs, bytes = staged.length()),
        )
            // A clip this device has no decoder for would place, then fall back to the built-in
            // presentation every time without a word. Refused here instead, by name.
            ?: if (spec.kind == UiMediaLimits.Kind.VIDEO) decodeCheck(staged) else null
    }.getOrElse {
        Timber.w(it, "MediaGate: probe failed for %s", staged.name)
        UiMediaLimits.MSG_UNDECODABLE
    }

    /**
     * Rewrites a staged WAV that is not plain 8/16-bit PCM (float, 24/32-bit, extensible) as 16-bit
     * PCM ([WavPcm16]). A WAV it cannot read is left as staged for the gate to judge.
     */
    private fun pcm16InPlace(staged: File) {
        if (!WavPcm16.needsConversion(staged)) return
        val converted = File(staged.parentFile, "${staged.name}.pcm16")
        try {
            if (!WavPcm16.convert(staged, converted)) return
            if (!converted.renameTo(staged)) converted.copyTo(staged, overwrite = true)
        } finally {
            converted.delete()
        }
    }
}
