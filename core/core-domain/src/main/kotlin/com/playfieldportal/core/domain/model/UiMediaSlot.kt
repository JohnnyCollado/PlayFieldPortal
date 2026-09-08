package com.playfieldportal.core.domain.model

import com.playfieldportal.themekit.UiMediaLimits

/**
 * Which media family a slot holds. Drives the accepted MIME set, the probe questions, and which
 * bulk reset touches it ("Reset Audio to Defaults" clears [SOUND] only — boot and GameBoot media
 * are separate screens' concerns).
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
 * The menu-sound roster is the seven-sound plan's: Navigation covers SCROLL, SELECT and
 * SYSTEM_BROWSE (one sample, three events — see [UiMediaSlot] docs in
 * docs/plans/sfx-seven-sounds-plan.md), and Boot Sound is the seventh row of the Sound screen
 * while staying an AUDIO_TRACK slot. Slots removed from this enum are swept from user installs
 * and restored backups by `UiMediaStore.pruneOrphans()`; their keys are deliberately NOT reused.
 */
enum class UiMediaSlot(
    val key: String,
    val kind: UiMediaKind,
    val displayName: String,
    val limits: UiMediaLimits.Spec,
) {
    // ── Menu sounds (Interface ▸ Sound) — the plan's seven-sound roster, in row order ──
    SOUND_SCROLL("sound_scroll", UiMediaKind.SOUND, "Navigation", UiMediaLimits.NAVIGATION),
    SOUND_BACK("sound_back", UiMediaKind.SOUND, "Back / Cancel", UiMediaLimits.BACK),
    SOUND_CONFIRM("sound_confirm", UiMediaKind.SOUND, "Confirm / Apply", UiMediaLimits.CONFIRM),
    SOUND_ERROR("sound_error", UiMediaKind.SOUND, "Error / Invalid", UiMediaLimits.ERROR),
    SOUND_LAUNCH("sound_launch", UiMediaKind.SOUND, "App Launch", UiMediaLimits.LAUNCH),
    SOUND_NOTIFICATION("sound_notification", UiMediaKind.SOUND, "Notification", UiMediaLimits.NOTIFICATION),

    // ── Boot sequence (Display ▸ Boot Sequence) ──────────────────────────────
    BOOT_VIDEO("boot_video", UiMediaKind.VIDEO, "Boot Animation", UiMediaLimits.BOOT_CLIP),
    BOOT_AUDIO("boot_audio", UiMediaKind.AUDIO_TRACK, "Boot Sound", UiMediaLimits.BOOT),

    // ── GameBoot (Display ▸ GameBoot) ────────────────────────────────────────
    GAMEBOOT_VIDEO("gameboot_video", UiMediaKind.VIDEO, "GameBoot Animation", UiMediaLimits.GAMEBOOT_CLIP),
    GAMEBOOT_AUDIO("gameboot_audio", UiMediaKind.AUDIO_TRACK, "GameBoot Sound", UiMediaLimits.GAMEBOOT),
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
