package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.achievement.AchievementProvider

/**
 * What the Shiba Coins overlay shows: a library game (resolved through its provider link) or an
 * account entry with no library copy (keyed directly by provider identity).
 */
sealed interface ShibaCoinsTarget {
    /**
     * [provider] names which set to open for a game that holds more than one (an owned Steam game
     * also played locally); null opens the one the game's link read reports.
     */
    data class LibraryGame(val gameId: Long, val provider: AchievementProvider? = null) : ShibaCoinsTarget

    data class AccountEntry(
        val provider: AchievementProvider,
        val providerGameId: String,
    ) : ShibaCoinsTarget
}
