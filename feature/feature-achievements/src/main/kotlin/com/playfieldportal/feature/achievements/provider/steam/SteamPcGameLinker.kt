package com.playfieldportal.feature.achievements.provider.steam

import com.playfieldportal.core.domain.achievement.AchievementProvider
import com.playfieldportal.feature.achievements.AchievementController
import com.playfieldportal.feature.launcher.PcGameAchievementLinker
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fulfils the shortcut importer's linker seam: a shortcut whose id carries a certain Steam appid
 * (GameNative `game_<appid>`, an explicit `steamAppId`/`app_id` intent extra) links STEAM at
 * import time — appid equality beats any title ladder.
 *
 * Only for a game in the user's Steam library ([WindowsSteamGate]). Any other is left unlinked:
 * a copy they do not own is tracked from its folder, which its achievements page asks for, and an
 * unknown one is theirs to answer there.
 */
@Singleton
class SteamPcGameLinker @Inject constructor(
    private val achievements: AchievementController,
    private val gate: WindowsSteamGate,
) : PcGameAchievementLinker {
    override suspend fun linkSteam(gameId: Long, appId: String) {
        val verdict = gate.verdict(appId)
        if (verdict != WindowsSteamGate.Verdict.OWNED) {
            Timber.i("STEAM not linked for game $gameId (appid $appId): $verdict")
            return
        }
        achievements.linkManually(gameId, AchievementProvider.STEAM, appId)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PcGameLinkerModule {
    @Binds
    abstract fun bindPcGameAchievementLinker(impl: SteamPcGameLinker): PcGameAchievementLinker
}
