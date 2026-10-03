package com.playfieldportal.feature.achievements.provider.steam

import com.playfieldportal.feature.achievements.api.RateLimiter
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Achievement descriptions from Steam Hunters, keyed by apiName — including the HIDDEN ones Steam's
 * Web API withholds. Shared by the STEAM and LOCAL_STEAM providers so both pace their requests on
 * one limiter. Best-effort: [byApiName] is null on any non-cancellation failure (HTTP error, an
 * oversized or unreadable body, JSON that isn't the expected list), and callers fall back.
 */
@Singleton
class SteamHuntersDescriptions @Inject constructor(
    private val api: SteamHuntersApi,
) {
    private val rate = RateLimiter(1_000)

    /** apiName → non-blank description for [appId]; empty when Steam Hunters doesn't know the game. */
    suspend fun byApiName(appId: String): Map<String, String>? =
        try {
            rate.await()
            api.achievements(appId).body()
                ?.readUtf8Capped(MAX_BYTES)
                ?.let(SteamHuntersAchievements::decode)
                ?.mapNotNull { entry ->
                    val apiName = entry.apiName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val description = entry.description?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    apiName to description
                }
                ?.toMap()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private companion object {
        // A Steam Hunters list is ~250 bytes per achievement (NieR's 47 are 11 KB); 2 MB covers the
        // largest sets with room to spare.
        const val MAX_BYTES = 2_000_000L
    }
}
