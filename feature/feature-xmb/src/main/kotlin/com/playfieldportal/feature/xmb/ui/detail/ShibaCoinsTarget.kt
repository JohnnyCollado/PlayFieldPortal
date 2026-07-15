package com.playfieldportal.feature.xmb.ui.detail

import com.playfieldportal.core.domain.achievement.AchievementProvider

/**
 * What the Shiba Coins overlay shows: a library game (resolved through its provider link) or an
 * account entry with no library copy (keyed directly by provider identity).
 */
sealed interface ShibaCoinsTarget {
    data class LibraryGame(val gameId: Long) : ShibaCoinsTarget

    data class AccountEntry(
        val provider: AchievementProvider,
        val providerGameId: String,
    ) : ShibaCoinsTarget
}
