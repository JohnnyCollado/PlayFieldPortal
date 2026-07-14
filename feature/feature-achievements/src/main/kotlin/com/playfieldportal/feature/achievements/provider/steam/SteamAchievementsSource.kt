package com.playfieldportal.feature.achievements.provider.steam

import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.provider.RemoteAchievementSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Steam coin-fetch strategy for PC (shortcut) games — a thin adapter over [SteamAchievementsApi]
 * behind the [RemoteAchievementSource] contract. Steam identity resolution (appid ladder, vanity
 * names) is a separate concern and stays with its own resolvers.
 */
@Singleton
class SteamAchievementsSource @Inject constructor(
    private val steamApi: SteamAchievementsApi,
) : RemoteAchievementSource {
    override suspend fun fetch(providerGameId: String): ProviderSyncResult = steamApi.fetch(providerGameId)
}
