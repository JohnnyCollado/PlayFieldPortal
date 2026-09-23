package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.provider.localsteam.LocalEarnedRead
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The launch-return check for one LOCAL_STEAM game (plan section 4, "Local Steam"): reads only that
 * game's local progress file and compares its earned-state fingerprint with the last one stored.
 * Unchanged → nothing is written and nothing is requested. Changed → the coins are rebuilt from the
 * cached schema and rarity, with zero Steam Web API calls; only a game with no cached metadata
 * falls back to one full fetch. A missing or unreadable file is "unknown" and changes nothing.
 *
 * Always invoked through [AchievementSyncCoordinator.checkLocalReturn], which debounces repeated
 * resumes and serializes it with any sync in flight.
 */
@Singleton
class LocalSteamReturnChecker @Inject constructor(
    private val source: LocalSteamSource,
    private val store: AchievementSyncStore,
    private val writer: AchievementSetWriter,
    private val clock: AchievementClock,
) {
    suspend fun check(gameId: Long): LocalReturnOutcome {
        val ledger = store.localSteamIdentityForGame(gameId) ?: return LocalReturnOutcome.NOT_LOCAL_STEAM
        val identity = AchievementIdentity(AchievementProvider.LOCAL_STEAM, ledger.providerGameId)

        val read = source.readEarned(identity.providerGameId) as? LocalEarnedRead.Read
            ?: return LocalReturnOutcome.UNKNOWN
        if (read.fingerprint == ledger.summarySnapshot) return LocalReturnOutcome.UNCHANGED

        val result = source.mapFromCache(identity.providerGameId, read)
            ?: source.fetch(identity.providerGameId)
        if (result !is ProviderSyncResult.Success) return LocalReturnOutcome.FAILED

        writer.write(identity, ledger.title, result.coins)
        store.recordDetail(identity, clock.now(), read.fingerprint)
        return LocalReturnOutcome.UPDATED
    }
}
