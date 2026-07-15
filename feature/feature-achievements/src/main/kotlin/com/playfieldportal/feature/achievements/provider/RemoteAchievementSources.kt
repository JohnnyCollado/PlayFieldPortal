package com.playfieldportal.feature.achievements.provider

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.feature.achievements.provider.localsteam.LocalSteamSource
import com.playfieldportal.feature.achievements.provider.retro.RetroAchievementsSource
import com.playfieldportal.feature.achievements.provider.steam.SteamAchievementsSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single place a game's provider is mapped to its remote source. Keeping the RA-vs-Steam
 * decision here — and nowhere else — is what keeps the two providers cleanly separated: each
 * strategy is self-contained in its own island, and they meet only through this one exhaustive
 * `when` (which fails to compile if a provider is ever added without a source).
 */
@Singleton
class RemoteAchievementSources @Inject constructor(
    private val retroAchievements: RetroAchievementsSource,
    private val steam: SteamAchievementsSource,
    private val localSteam: LocalSteamSource,
) {
    fun forProvider(provider: AchievementProvider): RemoteAchievementSource = when (provider) {
        AchievementProvider.RETRO_ACHIEVEMENTS -> retroAchievements
        AchievementProvider.STEAM -> steam
        AchievementProvider.LOCAL_STEAM -> localSteam
    }
}
