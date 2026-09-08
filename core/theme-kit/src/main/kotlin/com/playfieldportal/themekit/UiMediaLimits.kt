package com.playfieldportal.themekit

/**
 * Import gate for user-picked UI media — menu sounds (Interface ▸ Audio), the boot sequence's
 * video/audio, and GameBoot's video/audio. Same contract as [MotionLimits]: playback discipline
 * cannot rescue a file that should never have been accepted, so the gate runs BEFORE anything is
 * committed, and every rejection names its reason.
 *
 * Every number here is a judgment call rather than a hardware fact — which is why they live in
 * one object the tests point at: tuning a limit later is a one-file change. The byte caps
 * (2 MB per sound, 25 MB per video) are NEW judgment calls introduced with this feature — there
 * is no design-doc number behind them, only the same reasoning as [MotionLimits.MAX_BYTES]:
 * enough headroom for any sane sound/clip, small enough that a mistyped pick can't stall the
 * import on a low-memory handheld.
 *
 * Lives in theme-kit (pure JVM), not core-data, because BOTH sides of the feature need the same
 * numbers: the launcher's Android importer probes with MediaMetadataRetriever, and the desktop
 * Theme Studio must validate a future `.pfptheme` sound pack against exactly these caps before
 * embedding it. Two copies would drift.
 *
 * Tested by UiMediaLimitsTest, including the boundary at each cap and the drift pin that every
 * UiMediaSlot carries an entry.
 */
object UiMediaLimits {

    /** Which media family a slot accepts — drives both the MIME set and the probe questions. */
    enum class Kind { SOUND, VIDEO, AUDIO_TRACK }

    /**
     * The per-slot caps, from the design doc's table. [recommendedMinMs]..[recommendedMaxMs] is
     * advisory only (surfaced in the picker's helper text, never enforced); [hardMaxMs] and
     * [maxBytes] are the enforced caps. Durations are milliseconds.
     */
    data class Spec(
        val kind: Kind,
        val recommendedMinMs: Long,
        val recommendedMaxMs: Long,
        val hardMaxMs: Long,
        val maxBytes: Long,
    )

    // ── Duration caps (hard max) ─────────────────────────────────────────────
    const val SOUND_MAX_MS          = 500L     // Navigation / Category Change
    const val CONFIRM_MAX_MS        = 1_000L   // Confirm / Back / Error
    const val LAUNCH_MAX_MS         = 3_000L   // App Launch
    /**
     * Notification gets its own, looser cap rather than joining the Confirm family. It is not
     * navigation feedback: it fires at most a few times an hour, nothing is waiting on it, and a
     * chime with a tail is normal for the kind. The bundled default is exactly 1.00 s, so putting
     * it on the Confirm cap would leave the shipped sample sitting on the boundary — a file that
     * passes today and fails on a re-import the moment a decoder rounds 1000 up to 1001.
     */
    const val NOTIFICATION_MAX_MS   = 2_000L
    const val GAMEBOOT_MAX_MS       = 5_000L
    const val BOOT_MAX_MS           = 10_000L

    // ── Byte caps — NEW judgment calls, see the class KDoc ───────────────────
    const val SOUND_MAX_BYTES = 2L * 1024 * 1024
    const val VIDEO_MAX_BYTES = 25L * 1024 * 1024

    // ── Per-slot specs ───────────────────────────────────────────────────────
    val NAVIGATION   = Spec(Kind.SOUND,       50L,    250L,   SOUND_MAX_MS,   SOUND_MAX_BYTES)
    val CONFIRM      = Spec(Kind.SOUND,       100L,   500L,   CONFIRM_MAX_MS, SOUND_MAX_BYTES)
    val BACK         = Spec(Kind.SOUND,       100L,   500L,   CONFIRM_MAX_MS, SOUND_MAX_BYTES)
    val LAUNCH       = Spec(Kind.SOUND,       300L, 2_000L,   LAUNCH_MAX_MS,  SOUND_MAX_BYTES)
    val NOTIFICATION = Spec(Kind.SOUND,       200L, 1_500L,   NOTIFICATION_MAX_MS, SOUND_MAX_BYTES)
    val ERROR        = Spec(Kind.SOUND,       100L,   500L,   CONFIRM_MAX_MS, SOUND_MAX_BYTES)
    val GAMEBOOT     = Spec(Kind.AUDIO_TRACK, 1_000L, 5_000L, GAMEBOOT_MAX_MS, VIDEO_MAX_BYTES)
    val GAMEBOOT_CLIP = Spec(Kind.VIDEO,      1_000L, 5_000L, GAMEBOOT_MAX_MS, VIDEO_MAX_BYTES)
    val BOOT         = Spec(Kind.AUDIO_TRACK, 1_000L, 8_000L, BOOT_MAX_MS,    VIDEO_MAX_BYTES)
    val BOOT_CLIP    = Spec(Kind.VIDEO,       1_000L, 8_000L, BOOT_MAX_MS,    VIDEO_MAX_BYTES)

    /** Accepted audio containers. Audio MIME arrays come from this set verbatim. */
    val AUDIO_MIME = setOf(
        "audio/mpeg", "audio/wav", "audio/x-wav", "audio/ogg", "audio/mp4", "audio/mp4a-latm",
    )

    /**
     * Accepted video containers: MotionLimits' video entries MINUS the animated-image ones.
     * GIF is explicitly a non-goal for boot animation (design doc §3) — Coil's animated-image
     * decoder is the wrong tool for a one-shot boot presentation, and ExoPlayer can't play it.
     */
    val VIDEO_MIME = setOf("video/mp4", "video/webm")

    /** Rejection strings, surfaced verbatim through the importers' message channels. */
    const val MSG_UNSUPPORTED_FORMAT_AUDIO = "Unsupported format — use MP3, WAV, OGG, or M4A audio"
    const val MSG_UNSUPPORTED_FORMAT_VIDEO = "Unsupported format — use MP4 or WebM video"
    const val MSG_TOO_LARGE_BYTES_SOUND = "File is too large — sounds must be under 2 MB"
    const val MSG_TOO_LARGE_BYTES_VIDEO = "File is too large — videos must be under 25 MB"
    const val MSG_UNDECODABLE = "Couldn't read that file — try a different one"

    /**
     * Fired only when NEITHER MediaMetadataRetriever nor core-data's MediaDurationFallback
     * (container-header math for MP3 Xing/CBR and PCM WAV) could time the file. Header-readable
     * files are rescued before this message is produced.
     */
    const val MSG_NO_DURATION =
        "Couldn't read that file's length — try a different file, or convert it to MP3/WAV/OGG/M4A"

    /**
     * The probed media facts, collected by MediaMetadataRetriever at import time. [mime] may be
     * null when the picker couldn't report one; [durationMs] is NULL (not 0 — a zero length is a
     * real value for a degenerate file) when the container carries no readable duration.
     *
     * This is deliberately stricter than [MotionLimits.Probe], which tolerates `durationMs = 0`
     * for animated GIFs: here, a media the launcher must time (a boot presentation that must end)
     * with an unreadable duration is rejected outright, per the design doc's "if duration cannot
     * be read reliably, reject the assignment".
     */
    data class Probe(
        val mime: String?,
        val durationMs: Long?,
        val bytes: Long,
    )

    /**
     * Returns the rejection message for the first failed check, or null when the probe passes.
     * Order matters only for UX: the cheapest, most-likely-wrong checks run first.
     *
     * Duration is MANDATORY for every kind here — a [Kind.SOUND] assignment feeds SoundPool, and
     * a boot/gameboot clip feeds a timed presentation; both break with an unreadable length.
     */
    fun validate(spec: Spec, probe: Probe): String? = when {
        probe.mime == null || probe.mime !in mimesFor(spec.kind) ->
            if (spec.kind == Kind.VIDEO) MSG_UNSUPPORTED_FORMAT_VIDEO else MSG_UNSUPPORTED_FORMAT_AUDIO
        probe.durationMs == null -> MSG_NO_DURATION
        probe.durationMs > spec.hardMaxMs -> tooLong(spec)
        probe.bytes > spec.maxBytes -> tooLarge(spec)
        else -> null
    }

    /** The user-facing "too long" message, naming the slot's cap (e.g. "0.5 s or less"). */
    fun tooLong(spec: Spec): String = "That clip is too long — ${formatSeconds(spec.hardMaxMs)} or less"

    /** The user-facing "too large" message, naming the slot's byte cap. */
    fun tooLarge(spec: Spec): String =
        if (spec.kind == Kind.VIDEO) MSG_TOO_LARGE_BYTES_VIDEO else MSG_TOO_LARGE_BYTES_SOUND

    /**
     * File extension (no dot, any case) to the MIME [validate] expects, or null when the
     * extension is not a UI-media container at all. The launcher never needs this — Android's
     * content resolver reports a real MIME for a picked Uri — but the desktop Theme Studio has
     * only a filename, and a future sound-pack codec validates against the same mapping.
     */
    fun mimeForExtension(extension: String): String? = when (extension.lowercase()) {
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg", "oga" -> "audio/ogg"
        "m4a", "m4b", "mp4a" -> "audio/mp4"
        "mp4", "m4v" -> "video/mp4"
        "webm" -> "video/webm"
        else -> null
    }

    /**
     * Every extension [mimeForExtension] knows — the canonical list the drift tests iterate.
     * Adding a key to the mappings without adding it here means those tests silently stop
     * covering it (the same contract as MotionLimits.knownExtensions).
     */
    val knownUiMediaExtensions = setOf("mp3", "wav", "ogg", "oga", "m4a", "m4b", "mp4a", "mp4", "m4v", "webm")

    /**
     * The extension a validated file of [mime] is stored under. Derived FROM the validated MIME,
     * so a stored file's suffix always names its container by construction (m4a normalizes both
     * MP4-audio MIME spellings).
     */
    fun extensionForMime(mime: String): String? = when (mime.lowercase()) {
        "audio/mpeg" -> "mp3"
        "audio/wav", "audio/x-wav" -> "wav"
        "audio/ogg" -> "ogg"
        "audio/mp4", "audio/mp4a-latm" -> "m4a"
        "video/mp4" -> "mp4"
        "video/webm" -> "webm"
        else -> null
    }

    private fun mimesFor(kind: Kind): Set<String> =
        if (kind == Kind.VIDEO) VIDEO_MIME else AUDIO_MIME

    /** "500" → "0.5 s", "10000" → "10 s" — the form the rejection messages name caps in. */
    private fun formatSeconds(ms: Long): String {
        val whole = ms / 1000
        val frac = (ms % 1000) / 100
        return if (frac == 0L) "$whole s" else "$whole.$frac s"
    }
}
