package com.playfieldportal.feature.achievements.api

import com.playfieldportal.core.domain.achievement.ShibaTier

/** One coin as returned by a provider fetch, already resolved to its Shiba tier. */
data class SyncedCoin(
    val providerAchievementId: String,
    val title: String,
    val description: String,
    // BRONZE / SILVER / GOLD, derived from [globalRarity]. Platinum is minted locally on mastery.
    val tier: ShibaTier,
    val globalRarity: Double,
    val iconUrl: String?,
    val isHidden: Boolean,
    val isEarned: Boolean,
    // Counts toward the Platinum crown. RetroAchievements draws a hard line: only a hardcore
    // unlock earns mastery (a softcore unlock still banks the coin via [isEarned], but not the
    // crown). Steam has no hardcore/softcore split, so there it simply mirrors [isEarned].
    val earnedHardcore: Boolean,
    val earnedAt: Long?,
)

/**
 * Outcome of fetching a game's achievements from a provider. The non-success cases are first-class
 * results the UI renders directly (a clear inline state), never exceptions carrying a key or URL.
 */
sealed interface ProviderSyncResult {
    data class Success(val providerGameId: String, val coins: List<SyncedCoin>) : ProviderSyncResult

    /** No API key / identity configured for this provider. */
    data object MissingCredentials : ProviderSyncResult

    /** The game isn't linked to a provider id yet, so there's nothing to fetch. */
    data object NotLinked : ProviderSyncResult

    /** Steam only: the user's profile "Game details" are not public. */
    data object ProfileNotPublic : ProviderSyncResult

    /** The game has no achievement set on this provider. */
    data object NotFound : ProviderSyncResult

    /** Network or parse failure. [reason] is safe to show — it never contains a key. */
    data class Failed(val reason: String) : ProviderSyncResult
}
