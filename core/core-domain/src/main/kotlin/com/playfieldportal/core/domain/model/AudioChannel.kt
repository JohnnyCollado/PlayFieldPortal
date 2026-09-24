package com.playfieldportal.core.domain.model

/**
 * Every launcher sound the user can set a level for, and the ONE registry for their stable
 * preference keys.
 *
 * **Chrome only.** These are the launcher's own sounds. The music player and the video player
 * play the user's *own* media and are deliberately absent: they run at system volume and keep
 * their hardware volume keys, because scaling someone's album by a launcher setting is not what
 * a master volume means. Both still *suppress* ambience — that is a separate question with a
 * separate answer, and the asymmetry is intentional.
 *
 * **The roster, not the event list.** [NAVIGATION] is one channel covering the SCROLL, SELECT and
 * SYSTEM_BROWSE events, exactly as [UiMediaSlot.SOUND_SCROLL] is one slot covering the same
 * three. A channel matches a row the user can see, so the levels list and the assignment list
 * line up name for name.
 *
 * [key] is used verbatim as the `volume_<key>` preference name, so it is part of the storage
 * contract: renaming one silently resets that channel to full for every existing install.
 */
enum class AudioChannel(val key: String, val displayName: String) {
    // ── Menu sounds — one channel per assignable row ──────────────────────────
    NAVIGATION("navigation", "Navigation"),
    BACK("back", "Back / cancel"),
    CONFIRM("confirm", "Confirm / apply"),
    ERROR("error", "Error / invalid"),
    NOTIFICATION("notification", "Notification"),

    // ── Presentations — no MenuSound, addressed directly by their players ─────
    BOOT("boot", "Boot sequence"),
    GAMEBOOT("gameboot", "GameBoot"),
    AMBIENCE("ambience", "Ambience"),
    ;

    /** The preference name holding this channel's 0..1 level. */
    val preferenceKey: String get() = "volume_$key"

    companion object {
        /** The master level's preference name — not a channel, so it is not an enum member. */
        const val MASTER_PREFERENCE_KEY = "volume_master"

        /** Every preference name this model owns, for the backup key list and its coverage test. */
        val ALL_PREFERENCE_KEYS: List<String> =
            listOf(MASTER_PREFERENCE_KEY) + entries.map { it.preferenceKey }
    }
}
