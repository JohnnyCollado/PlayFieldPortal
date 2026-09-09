package com.playfieldportal.themekit

/**
 * Import gate for user-picked UI media — menu sounds (Interface ▸ Sound), the boot sequence's
 * video/audio, and GameBoot's one replaceable clip. Same contract as [MotionLimits]: playback discipline
 * cannot rescue a file that should never have been accepted, so the gate runs BEFORE anything is
 * committed, and every rejection names its reason.
 *
 * Every number here is a judgment call rather than a hardware fact — which is why they live in
 * one object the tests point at: tuning a limit later is a one-file change.
 *
 * AUDIO policy (owner decision, 2026-09-08): **no floor and no user-facing byte cap.** The
 * recommended range is advisory only and every audio spec's floor is zero — a 0.2 s click is a
 * legitimate Navigation sound. The lag/abuse protection is [Spec.hardMaxMs]: SoundPool decodes a
 * custom menu sound fully into memory, so the per-event duration ceilings (0.5–3 s) are what
 * bound the decoded footprint, and a boot track streams through ExoPlayer where its 10 s cap
 * bounds the presentation instead. The audio [Spec.maxBytes] values are NOT a quality limit —
 * they are the staging-copy ceiling (see [AUDIO_STAGE_MAX_BYTES]) that stops a malicious picker
 * from streaming gigabytes into app storage before the duration gate can reject the file.
 *
 * VIDEO keeps both a floor and a real byte cap: a clip feeds a timed presentation and is probed
 * with MediaMetadataRetriever, and 25 MB comfortably covers any sane MP4/WebM of the allowed
 * lengths.
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
     * The per-slot caps. [recommendedMinMs]..[recommendedMaxMs] is advisory only (surfaced in
     * the picker's helper text, never enforced; audio specs carry a zero floor — no minimum);
     * [hardMaxMs] and [maxBytes] are the enforced caps. For audio, [maxBytes] is the staging
     * anti-DoS ceiling, not a user-facing limit — see the class KDoc. Durations are milliseconds.
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
    const val LAUNCH_MAX_MS         = 3_000L   // Launch Sound
    /**
     * Notification gets its own, looser cap rather than joining the Confirm family. It is not
     * navigation feedback: it fires at most a few times an hour, nothing is waiting on it, and a
     * chime with a tail is normal for the kind. The bundled default is 1.056 s (1.00 s before the
     * 2026-09-08 sound-set swap), so putting it on the Confirm cap would leave the shipped sample
     * over the boundary outright — and even at exactly 1.00 s it would have sat on it, a file that
     * passes today and fails on a re-import the moment a decoder rounds 1000 up to 1001.
     */
    const val NOTIFICATION_MAX_MS   = 2_000L
    /**
     * The built-in GameBoot sequence and the sample it is beat-matched to (`sfx_launch`, exactly
     * 5.000 s). NOT a user-facing import cap — nothing is imported against this; it is what the
     * gate clips the bundled sound to. See [GAMEBOOT_CLIP_MAX_MS] for what a user may assign.
     */
    const val GAMEBOOT_SEQUENCE_MS  = 5_000L
    /**
     * A user's own GameBoot clip may run twice the built-in sequence, matching the boot clip's
     * ceiling: the presentation is theirs to author, and 5 s was too tight for anything with a
     * build and a payoff. The launch waits for the whole thing either way, so this is the number
     * the two watchdogs behind it are sized from (GameBootOverlay's video cap, GameBootGate's).
     */
    const val GAMEBOOT_CLIP_MAX_MS  = 10_000L
    const val BOOT_MAX_MS           = 10_000L

    // ── Byte caps ────────────────────────────────────────────────────────────
    /**
     * VIDEO_MAX_BYTES is a real cap: video bytes are never held in memory (the staging copy
     * streams) and 25 MB covers any sane clip of the allowed lengths.
     *
     * AUDIO_STAGE_MAX_BYTES is NOT a quality bar — it is how much of a picked audio file the
     * staged copy will accept before giving up, so a crafted pick cannot stream gigabytes into
     * app storage (or hold the import coroutine forever) before the duration gate rejects it.
     * 128 MB is roughly two hours of 160 kbps audio — far beyond anything the duration ceilings
     * let through, and streamed to disk in 64 KB chunks, never held on the heap.
     */
    const val AUDIO_STAGE_MAX_BYTES = 128L * 1024 * 1024
    const val VIDEO_MAX_BYTES = 25L * 1024 * 1024

    // ── Per-slot specs ───────────────────────────────────────────────────────
    // Every AUDIO spec: zero floor (no minimum length), maxBytes = the staging ceiling.
    val NAVIGATION   = Spec(Kind.SOUND,       0L,    250L,   SOUND_MAX_MS,   AUDIO_STAGE_MAX_BYTES)
    val CONFIRM      = Spec(Kind.SOUND,       0L,   500L,   CONFIRM_MAX_MS, AUDIO_STAGE_MAX_BYTES)
    val BACK         = Spec(Kind.SOUND,       0L,   500L,   CONFIRM_MAX_MS, AUDIO_STAGE_MAX_BYTES)
    val LAUNCH       = Spec(Kind.SOUND,       0L, 2_000L,   LAUNCH_MAX_MS,  AUDIO_STAGE_MAX_BYTES)
    val NOTIFICATION = Spec(Kind.SOUND,       0L, 1_500L,   NOTIFICATION_MAX_MS, AUDIO_STAGE_MAX_BYTES)
    val ERROR        = Spec(Kind.SOUND,       0L,   500L,   CONFIRM_MAX_MS, AUDIO_STAGE_MAX_BYTES)
    // GameBoot has ONE slot: the user's replaceable clip. There is no GameBoot audio spec
    // because there is no GameBoot audio slot — the built-in sequence's own sound is bundled,
    // not imported, and GAMEBOOT_SEQUENCE_MS is what the gate clips it to.
    val GAMEBOOT_CLIP = Spec(Kind.VIDEO,      1_000L, 8_000L, GAMEBOOT_CLIP_MAX_MS, VIDEO_MAX_BYTES)
    val BOOT         = Spec(Kind.AUDIO_TRACK, 0L, 8_000L, BOOT_MAX_MS,    AUDIO_STAGE_MAX_BYTES)
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
    const val MSG_TOO_LARGE_BYTES_SOUND = "File is too large"
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

    /**
     * The user-facing "too large" message. For audio this is the unreachable-in-practice staging
     * ceiling (a file that big is rejected by the duration gate first); for video it is the real
     * cap.
     */
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
