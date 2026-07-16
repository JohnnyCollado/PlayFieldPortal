package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.feature.achievements.api.RateLimiter
import com.playfieldportal.feature.achievements.api.SyncedCoin
import com.playfieldportal.feature.achievements.provider.steam.SteamCommunityAchievementsParser
import com.playfieldportal.feature.achievements.provider.steam.SteamCommunityApi
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fills the descriptions of EARNED hidden achievements on emu (LOCAL_STEAM) games — the one field
 * the Steam Web API withholds permanently. The STEAM provider reads these off the user's OWN
 * community page ([com.playfieldportal.feature.achievements.provider.steam.SteamRemoteDataSource]),
 * but an emu game isn't owned, so that page doesn't exist. Instead this reads the community
 * achievements pages of a fixed roster of large public "top owner" profiles (the same accounts
 * Goldberg's config tooling leans on): a hidden achievement's description shows on the page of
 * anyone who has unlocked it, and a completionist owner covers a whole game in one fetch.
 *
 * Strictly best-effort and non-fatal, matching the STEAM path: only runs when an earned hidden coin
 * still lacks a description, stops as soon as every needed description is found, caps the profiles
 * it tries, and treats every page as untrusted display HTML (see [SteamCommunityAchievementsParser]).
 * Any failure — a private profile, a markup change, a network error — leaves the coins untouched,
 * so the UI's redacted-description fallback stays in place. Cancellation always propagates.
 */
@Singleton
class LocalSteamHiddenDescriptions @Inject constructor(
    private val communityApi: SteamCommunityApi,
) {
    private val rate = RateLimiter(1_100)

    suspend fun enrich(appId: String, coins: List<SyncedCoin>): List<SyncedCoin> {
        // Same gate as the STEAM own-profile enrichment: only earned hidden coins whose description
        // Steam has redacted. Unearned hidden achievements keep their surprise.
        val wanted = coins.asSequence()
            .filter { it.isHidden && it.isEarned && it.description.isBlank() }
            .map { SteamCommunityAchievementsParser.normalizeTitle(it.title) }
            .toSet()
        if (wanted.isEmpty()) return coins

        val found = mutableMapOf<String, String>()
        for (ownerId in TOP_OWNER_IDS) {
            if (found.keys.containsAll(wanted)) break // full coverage — stop early
            val page = fetchPage(ownerId, appId) ?: continue
            val descriptionByTitle = SteamCommunityAchievementsParser.parse(page)
            // First owner to reveal a title wins; only keep the ones we still need.
            for (title in wanted) {
                if (title !in found) descriptionByTitle[title]?.let { found[title] = it }
            }
        }
        if (found.isEmpty()) return coins

        return coins.map { coin ->
            if (!coin.isHidden || !coin.isEarned || coin.description.isNotBlank()) return@map coin
            found[SteamCommunityAchievementsParser.normalizeTitle(coin.title)]
                ?.let { coin.copy(description = it) } ?: coin
        }
    }

    // One owner's achievements page as text, or null on any non-cancellation failure. Cancellation
    // must propagate, so it is caught and rethrown ahead of the catch-all.
    private suspend fun fetchPage(ownerId: String, appId: String): String? =
        try {
            rate.await()
            communityApi.achievementsPage(ownerId, appId)
                .body()?.takeIf { it.contentLength() <= MAX_PAGE_BYTES }?.string()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private companion object {
        // Public profiles tried in order for a page that reveals hidden descriptions; a private
        // or missing one is skipped, and the walk stops at the first that covers every needed
        // achievement. Two tiers, verified-first so coverage lands in the fewest fetches:
        //   - hand-verified completionists with PUBLIC game details (checked 2026-07-16),
        //   - the broad-library roster Goldberg's generate_emu_config ships, as a fallback.
        // Many big completionists hide game details, so the verified tier is grown per popular
        // game as gaps surface (FF VI's hidden set, e.g., was covered by neither original entry).
        val TOP_OWNER_IDS = listOf(
            // Verified public completionists (games perfected are noted for future auditing).
            "76561198010615256", // lylat — FF VI 37/37
            "76561197983291252", // jedo — FF VI 37/37
            // Goldberg's original broad-library roster.
            "76561198028121353", "76561198001237877", "76561198355625888", "76561198001678750",
            "76561198237402290", "76561197979911851", "76561198152618007", "76561197969050296",
            "76561198213148949", "76561198037867621", "76561198108581917",
        )

        // An achievements page is a few hundred KB; anything larger is not the page we expect.
        const val MAX_PAGE_BYTES = 4_000_000L
    }
}
