package com.playfieldportal.themekit

/**
 * The UI-media entries a `.pfptheme` can carry (plan 5.2): the five menu sounds, ambience, the
 * boot clip and the GameBoot clip.
 *
 * Keys are IDENTICAL to the launcher's `UiMediaSlot` keys — core-domain's UiMediaSlotTest pins
 * that, since theme-kit cannot depend on the enum. Entry names:
 *
 * ```
 * sounds/<sound_scroll|sound_back|sound_confirm|sound_error|sound_notification>.<mp3|wav|ogg|m4a>
 * ambience.<mp3|wav|ogg|m4a>
 * boot.<mp4|webm>
 * gameboot.<mp4|webm>
 * ```
 *
 * Gating is by slot key, kind and extension ([UiMediaLimits.mimeForExtension]); size and duration
 * caps are the slot's [Slot.spec] and [Slot.maxBytes], enforced by the codec on read (bytes) and
 * by the apply-side gate (duration).
 */
object ThemeMediaSlots {

    /**
     * One bundle-carriable media slot. [stem] is the entry's name without extension
     * (`sounds/sound_back`, `ambience`, ...).
     */
    class Slot internal constructor(
        val key: String,
        val kind: UiMediaLimits.Kind,
        val spec: UiMediaLimits.Spec,
        val stem: String,
        /** Cap on the entry's bytes inside a bundle; tighter than [UiMediaLimits.Spec.maxBytes] for audio. */
        val maxBytes: Long,
    ) {
        /** Extensions this slot accepts, lowercase, no dot. */
        val extensions: Set<String> =
            if (kind == UiMediaLimits.Kind.VIDEO) VIDEO_EXTENSIONS else AUDIO_EXTENSIONS

        /** True when [extension] (any case) is a container this slot takes. */
        fun accepts(extension: String): Boolean {
            val ext = extension.lowercase()
            if (ext !in extensions) return false
            val mime = UiMediaLimits.mimeForExtension(ext) ?: return false
            return mime in if (kind == UiMediaLimits.Kind.VIDEO) UiMediaLimits.VIDEO_MIME else UiMediaLimits.AUDIO_MIME
        }

        /** The zip entry name for [extension], without checking [accepts]. */
        fun entryName(extension: String): String = "$stem.${extension.lowercase()}"
    }

    private const val SOUNDS_DIR = "sounds/"

    val AUDIO_EXTENSIONS = setOf("mp3", "wav", "ogg", "m4a")
    val VIDEO_EXTENSIONS = setOf("mp4", "webm")

    private fun sound(key: String, spec: UiMediaLimits.Spec) =
        Slot(key, spec.kind, spec, "$SOUNDS_DIR$key", UiMediaLimits.THEME_SOUND_MAX_BYTES)

    /** Every slot, in the launcher's `UiMediaSlot` order. */
    val ALL: List<Slot> = listOf(
        sound("sound_scroll", UiMediaLimits.NAVIGATION),
        sound("sound_back", UiMediaLimits.BACK),
        sound("sound_confirm", UiMediaLimits.CONFIRM),
        sound("sound_error", UiMediaLimits.ERROR),
        sound("sound_notification", UiMediaLimits.NOTIFICATION),
        Slot("boot_video", UiMediaLimits.BOOT_CLIP.kind, UiMediaLimits.BOOT_CLIP, "boot", UiMediaLimits.VIDEO_MAX_BYTES),
        Slot("gameboot_video", UiMediaLimits.GAMEBOOT_CLIP.kind, UiMediaLimits.GAMEBOOT_CLIP, "gameboot", UiMediaLimits.VIDEO_MAX_BYTES),
        Slot("ambience_audio", UiMediaLimits.AMBIENCE.kind, UiMediaLimits.AMBIENCE, "ambience", UiMediaLimits.THEME_AMBIENCE_MAX_BYTES),
    )

    val KEYS: Set<String> = ALL.map { it.key }.toSet()

    private val byKey = ALL.associateBy { it.key }
    private val byStem = ALL.associateBy { it.stem }

    fun slot(key: String): Slot? = byKey[key]

    fun isValidKey(key: String): Boolean = key in byKey

    /** The zip entry name for [key] stored as [extension], or null when either is not accepted. */
    fun entryName(key: String, extension: String): String? =
        byKey[key]?.takeIf { it.accepts(extension) }?.entryName(extension)

    /**
     * The slot whose entry stem [entryName] claims, whatever its extension — so `boot.gif` is
     * recognised as a misplaced boot clip rather than an unknown file. Null for every other name,
     * including a retired sound key (`sounds/sound_launch.mp3`).
     */
    fun claimedBy(entryName: String): Slot? =
        byStem[entryName.substringBeforeLast('.', "")]

    /** The slot for [entryName] only when its extension is also accepted. */
    fun slotForEntry(entryName: String): Slot? =
        claimedBy(entryName)?.takeIf { it.accepts(entryName.substringAfterLast('.')) }
}
