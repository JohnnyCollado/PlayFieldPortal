package com.playfieldportal.feature.achievements.sync

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamSource
import com.playfieldportal.feature.achievements.provider.retro.RetroAchievementsSource
import com.playfieldportal.feature.achievements.provider.vita.VitaTrophySource
import javax.inject.Inject
import javax.inject.Singleton

/** One full-detail read of a tracked identity. Read-only: persisting is the writer's job. */
interface AchievementDetailFetcher {
    suspend fun fetch(identity: AchievementIdentity, reason: FetchReason): ProviderSyncResult
}

/**
 * Routes a full-detail fetch to its provider: RA's GetGameInfoAndUserProgress, Steam's tiered
 * [SteamDetailFetcher], Local Steam's file + cached metadata, Vita's local trophy files.
 */
@Singleton
class DefaultAchievementDetailFetcher @Inject constructor(
    private val retro: RetroAchievementsSource,
    private val steam: SteamDetailFetcher,
    private val localSteam: LocalSteamSource,
    private val vita: VitaTrophySource,
) : AchievementDetailFetcher {

    override suspend fun fetch(identity: AchievementIdentity, reason: FetchReason): ProviderSyncResult {
        val id = identity.providerGameId
        return when (identity.provider) {
            AchievementProvider.RETRO_ACHIEVEMENTS -> retro.fetch(id)
            AchievementProvider.STEAM -> steam.fetch(id, reason)
            // Only an explicit refresh repairs Local Steam metadata; everything else stays local.
            AchievementProvider.LOCAL_STEAM -> localSteam.fetch(id, renewMetadata = reason == FetchReason.EXPLICIT)
            AchievementProvider.VITA_TROPHY -> vita.fetch(id)
        }
    }
}
