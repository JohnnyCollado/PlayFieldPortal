package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.feature.achievements.api.RateLimiter
import com.playfieldportal.feature.achievements.api.SyncedCoin
import com.playfieldportal.feature.achievements.provider.steam.SteamCommunityAchievementsParser
import com.playfieldportal.feature.achievements.provider.steam.SteamCommunityApi
import com.playfieldportal.feature.achievements.provider.steam.SteamHuntersDescriptions
import com.playfieldportal.feature.achievements.provider.steam.readUtf8Capped
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fills the descriptions of hidden achievements on emu (LOCAL_STEAM) games — the one field the Steam
 * Web API withholds permanently. The STEAM provider reads these off the user's OWN community page
 * ([com.playfieldportal.feature.achievements.provider.steam.SteamRemoteDataSource]), but an emu game
 * isn't owned, so that page doesn't exist. Two sources instead, in order:
 *
 * 1. **Steam Hunters** ([SteamHuntersDescriptions]) — one keyless request returns every achievement's
 *    description, matched to coins exactly by apiName. Fills hidden coins whether earned or not: the
 *    surprise of an unearned one is kept by the UI's redaction, not by a blank description, and
 *    having the text stored early means it is already there on the day the coin is earned.
 * 2. **A roster of public "top owner" profiles** (the accounts Goldberg's config tooling leans on),
 *    for EARNED hidden coins Steam Hunters left blank. A hidden achievement's description shows on
 *    the page of anyone who unlocked it, matched by normalized title.
 *
 * Strictly best-effort and non-fatal: makes no request when no hidden coin is blank, never
 * overwrites a description, caps every body it reads, and treats all responses as untrusted display
 * text. Any failure — Steam Hunters down or unaware of the game, a private profile, a markup change —
 * falls through to the next source and finally leaves the coins as they were, so the UI's
 * redacted-description fallback stays in place. Cancellation always propagates.
 */
@Singleton
class LocalSteamHiddenDescriptions @Inject constructor(
    private val communityApi: SteamCommunityApi,
    private val hunters: SteamHuntersDescriptions,
) {
    private val rate = RateLimiter(1_100)

    suspend fun enrich(appId: String, coins: List<SyncedCoin>): List<SyncedCoin> {
        if (coins.none { it.needsDescription() }) return coins
        return fillFromRoster(appId, fillFromSteamHunters(appId, coins))
    }

    private suspend fun fillFromSteamHunters(appId: String, coins: List<SyncedCoin>): List<SyncedCoin> {
        val descriptionByApiName = hunters.byApiName(appId) ?: return coins
        return coins.map { coin ->
            if (!coin.needsDescription()) return@map coin
            descriptionByApiName[coin.providerAchievementId]?.let { coin.copy(description = it) } ?: coin
        }
    }

    private suspend fun fillFromRoster(appId: String, coins: List<SyncedCoin>): List<SyncedCoin> {
        // Earned only: an owner page reveals just what that owner unlocked, so asking about unearned
        // coins would mostly fan out across the roster for nothing.
        val wanted = coins.asSequence()
            .filter { it.needsDescription() && it.isEarned }
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
            if (!coin.needsDescription() || !coin.isEarned) return@map coin
            found[SteamCommunityAchievementsParser.normalizeTitle(coin.title)]
                ?.let { coin.copy(description = it) } ?: coin
        }
    }

    private fun SyncedCoin.needsDescription() = isHidden && description.isBlank()

    // One owner's achievements page as text, or null on any non-cancellation failure. Cancellation
    // must propagate, so it is caught and rethrown ahead of the catch-all.
    private suspend fun fetchPage(ownerId: String, appId: String): String? =
        try {
            rate.await()
            // Capped while reading: gzip pages arrive with no Content-Length to check up front.
            communityApi.achievementsPage(ownerId, appId).body()?.readUtf8Capped(MAX_PAGE_BYTES)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }

    private companion object {
        // Public profiles tried in order for a page that reveals hidden descriptions; a private
        // or missing one is skipped, and the walk stops at the first that covers every needed
        // achievement. Three tiers, verified-first so coverage lands in the fewest fetches:
        //   - hand-verified completionists with PUBLIC game details (checked 2026-07-16),
        //   - Steam Hunters' leaderboard leaders, public with readable achievement pages
        //     (checked 2026-09-29 on 367520, 524220, 620),
        //   - the broad-library roster Goldberg's generate_emu_config ships, as a fallback.
        // Many big completionists hide game details, so the verified tier is grown per popular
        // game as gaps surface (FF VI's hidden set, e.g., was covered by neither original entry).
        val TOP_OWNER_IDS = listOf(
            // Verified public completionists (games perfected are noted for future auditing).
            "76561198010615256", // lylat — FF VI 37/37
            "76561197983291252", // jedo — FF VI 37/37
            // Steam Hunters leaders.
            "76561197971398453", // NEXGEN -EZ- — fullest coverage in the 2026-09-29 check
            "76561198040673812", // The Stranger
            "76561198019373005", // Parzival
            "76561197977849691", // DDtective
            "76561198155124847", // AFAK
            // Goldberg's original broad-library roster.
            "76561198028121353", "76561198001237877", "76561198355625888", "76561198001678750",
            "76561198237402290", "76561197979911851", "76561198152618007", "76561197969050296",
            "76561198213148949", "76561198037867621", "76561198108581917",
        )

        // An achievements page is a few hundred KB; anything larger is not the page we expect.
        const val MAX_PAGE_BYTES = 4_000_000L
    }
}
