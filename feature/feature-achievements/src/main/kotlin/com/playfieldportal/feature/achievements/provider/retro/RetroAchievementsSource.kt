package com.playfieldportal.feature.achievements.provider.retro

import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.provider.RemoteAchievementSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The RetroAchievements coin-fetch strategy — a thin adapter over [RaRemoteDataSource] that keeps
 * the api-kotlin stack behind the [RemoteAchievementSource] contract. Hash-only game identification
 * is a separate concern, handled by [RaHashResolver].
 */
@Singleton
class RetroAchievementsSource @Inject constructor(
    private val remote: RaRemoteDataSource,
) : RemoteAchievementSource {
    override suspend fun fetch(providerGameId: String): ProviderSyncResult = remote.fetch(providerGameId)
}
