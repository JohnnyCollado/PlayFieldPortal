package com.playfieldportal.core.domain.achievement

/**
 * Where a game's achievements come from. Both are read-only public-data services; neither ever
 * involves a password. See docs/shiba-coins-achievements-plan.md.
 */
enum class AchievementProvider {
    RETRO_ACHIEVEMENTS, // emulated titles, matched by ROM content hash
    STEAM;              // PC titles, matched by Steam appid

    companion object {
        fun fromName(name: String?): AchievementProvider? =
            entries.firstOrNull { it.name == name }
    }
}
