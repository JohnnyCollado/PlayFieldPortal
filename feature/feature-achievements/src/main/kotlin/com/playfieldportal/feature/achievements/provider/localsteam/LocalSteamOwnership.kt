package com.playfieldportal.feature.achievements.provider.localsteam

import com.playfieldportal.core.data.database.dao.ProviderGameLinkDao
import com.playfieldportal.core.data.database.dao.SteamOwnedGamesDao
import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.core.domain.achievement.LocalCopyOwnership
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Derives and persists the owned-vs-local classification for LOCAL_STEAM links (section 5 of
 * docs/local-steam-achievements-plan.md): the discovered appid against the Steam owned-games
 * cache. An empty cache means UNKNOWN (null) — `isOwned == false` alone never becomes
 * NOT_IN_LIBRARY, so classification never guesses. Refreshed by every scan and by every
 * completed Steam account import.
 */
@Singleton
class LocalSteamOwnership @Inject constructor(
    private val ownedDao: SteamOwnedGamesDao,
    private val linkDao: ProviderGameLinkDao,
) {
    /** The classification for [appId] right now, or null while the owned cache is unpopulated. */
    suspend fun derive(appId: String): LocalCopyOwnership? {
        if (ownedDao.ownedCount() == 0) return null
        return if (ownedDao.isOwned(appId)) LocalCopyOwnership.OWNED else LocalCopyOwnership.NOT_IN_LIBRARY
    }

    /** Derives and stores the classification on [gameId]'s LOCAL_STEAM link. */
    suspend fun classify(gameId: Long, appId: String): LocalCopyOwnership? {
        val ownership = derive(appId)
        linkDao.setOwnership(gameId, AchievementProvider.LOCAL_STEAM.name, ownership?.name)
        return ownership
    }

    /** Re-derives every LOCAL_STEAM link — the post-import upgrade path for UNKNOWN states. */
    suspend fun refreshAll() {
        val links = linkDao.getByProvider(AchievementProvider.LOCAL_STEAM.name)
        links.forEach { link ->
            linkDao.setOwnership(link.gameId, link.provider, derive(link.providerGameId)?.name)
        }
        if (links.isNotEmpty()) Timber.i("LOCAL_STEAM ownership refreshed for ${links.size} link(s)")
    }
}
