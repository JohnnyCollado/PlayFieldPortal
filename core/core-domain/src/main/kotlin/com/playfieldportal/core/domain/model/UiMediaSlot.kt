package com.playfieldportal.core.domain.model

import com.playfieldportal.themekit.UiMediaLimits

/**
 * Which media family a slot holds. Drives the accepted MIME set, the probe questions, and which
 * bulk reset touches it ("Reset Audio to Defaults" clears [SOUND] only — boot and GameBoot media
 * are separate screens' concerns).
 *
 * [AUDIO_TRACK] is audio the user assigns that is NOT a SoundPool menu sample — seconds or
 * minutes of playback through ExoPlayer rather than a UI tick. [UiMediaSlot.AMBIENCE_AUDIO] is
 * its member. A presentation's own sound is not one of these: Boot Sequence and GameBoot carry
 * their audio inside the clip the user replaces, which is why neither has a slot here.
 */
enum class UiMediaKind { SOUND, VIDEO, AUDIO_TRACK }

/**
 * Every UI-media slot a user can personalize, and the ONE registry for their stable storage keys.
 *
 * A slot's file lives at `filesDir/ui-media/<key>.<ext>` — [key] is used verbatim as the file
 * name, which makes [isValidKey] load-bearing: it is what stops a crafted key escaping the
 * directory (the same guard pattern as theme-kit's `CustomizableIcons.isValidKey`). The enum
 * makes an escape impossible through the API surface today; the guard exists so the invariant
 * is enforced in one place rather than assumed by every caller.
 *
 * [limits] carries this slot's caps from the design doc's table — see [UiMediaLimits].
 *
 * The menu-sound roster is five rows: Navigation covers SCROLL, SELECT and SYSTEM_BROWSE (one
 * sample, three events — see [UiMediaSlot] docs in docs/plans/README.md (C10)), then Back,
 * Confirm, Error and Notification.
 *
 * **A presentation's sound is part of the presentation, not a slot.** Boot Sequence and GameBoot
 * are each ONE thing the user either keeps or replaces wholesale with a clip of their own, which
 * brings its own audio track. That is why neither has an audio slot here and why there is no
 * launch sound: an app opening is silent, and a game opening is scored by GameBoot or by nothing
 * at all. The retired `sound_launch`, `boot_audio` and `gameboot_audio` keys are swept from
 * installs and restored backups by `UiMediaStore.pruneOrphans()`.
 *
 * Ambience is the exception that proves the rule: it is not a presentation but a continuous
 * background track, so it IS assignable on its own — see [AMBIENCE_AUDIO].
 *
 * Slots removed from this enum are swept the same way; their keys are deliberately NOT reused.
 */
enum class UiMediaSlot(
    val key: String,
    val kind: UiMediaKind,
    val displayName: String,
    val limits: UiMediaLimits.Spec,
) {
    // ── Menu sounds (Interface ▸ Sound) — the roster, in row order ──
    SOUND_SCROLL("sound_scroll", UiMediaKind.SOUND, "Navigation", UiMediaLimits.NAVIGATION),
    SOUND_BACK("sound_back", UiMediaKind.SOUND, "Back / Cancel", UiMediaLimits.BACK),
    SOUND_CONFIRM("sound_confirm", UiMediaKind.SOUND, "Confirm / Apply", UiMediaLimits.CONFIRM),
    SOUND_ERROR("sound_error", UiMediaKind.SOUND, "Error / Invalid", UiMediaLimits.ERROR),
    SOUND_NOTIFICATION("sound_notification", UiMediaKind.SOUND, "Notification", UiMediaLimits.NOTIFICATION),

    // ── Boot sequence (Display ▸ Boot Sequence) ──────────────────────────────
    // ONE slot, for the same reason GameBoot has one: the boot presentation is a single thing the
    // user either keeps or replaces wholesale with their own clip, which brings its own audio.
    // The retired `boot_audio` AUDIO_TRACK slot is swept by pruneOrphans(); the built-in
    // sequence's chime is now baked in as UiMediaDefaults.bootDefaultAudioUri().
    BOOT_VIDEO("boot_video", UiMediaKind.VIDEO, "Boot Animation", UiMediaLimits.BOOT_CLIP),

    // ── GameBoot (Display ▸ GameBoot) ────────────────────────────────────────
    // ONE slot, symmetrically. The retired `gameboot_audio` AUDIO_TRACK slot is swept the same way.
    GAMEBOOT_VIDEO("gameboot_video", UiMediaKind.VIDEO, "GameBoot Animation", UiMediaLimits.GAMEBOOT_CLIP),

    // ── Ambience (Interface ▸ Sound) ─────────────────────────────────────────
    // The launcher's background music. Unlike every slot above it this is CONTINUOUS, which is
    // what forced the volume model to exist: a menu tick only ever needs on or off, a loop needs
    // a level. No assignment means ambience is off — the assignment IS the switch, so there is no
    // separate enable flag that could drift out of sync with it.
    AMBIENCE_AUDIO("ambience_audio", UiMediaKind.AUDIO_TRACK, "Ambience Track", UiMediaLimits.AMBIENCE),
    ;

    val isSound: Boolean get() = kind == UiMediaKind.SOUND

    companion object {
        private val byKey: Map<String, UiMediaSlot> = entries.associateBy { it.key }

        fun fromKey(key: String): UiMediaSlot? = byKey[key]

        fun isValidKey(key: String): Boolean = key in byKey

        /** Every slot of one kind — the settings screens iterate these. */
        fun ofKind(kind: UiMediaKind): List<UiMediaSlot> = entries.filter { it.kind == kind }
    }
}
