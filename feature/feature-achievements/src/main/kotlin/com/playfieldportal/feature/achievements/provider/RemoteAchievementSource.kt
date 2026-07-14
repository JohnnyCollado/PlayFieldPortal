package com.playfieldportal.feature.achievements.provider

import com.playfieldportal.feature.achievements.api.ProviderSyncResult

/**
 * A provider's read-only coin fetch, normalized to domain types. Each provider
 * (RetroAchievements, Steam) implements this inside its own island under `provider/`; the two
 * never share a code path and converge only at [RemoteAchievementSources]. This seam is what lets
 * the coin system stay provider-agnostic while keeping RA and Steam cleanly separated. See
 * docs/shiba-coins-achievements-plan.md.
 */
interface RemoteAchievementSource {
    /** Fetches [providerGameId]'s coins and this user's earned state, or a first-class non-success. */
    suspend fun fetch(providerGameId: String): ProviderSyncResult
}
