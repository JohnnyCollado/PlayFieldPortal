package com.playfieldportal.feature.achievements.provider.x360

import com.playfieldportal.core.domain.achievement.ShibaTier
import com.playfieldportal.feature.achievements.api.ProviderSyncResult
import com.playfieldportal.feature.achievements.api.SyncedCoin
import com.playfieldportal.feature.achievements.provider.RemoteAchievementSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The X360_ACHIEVEMENT provider: an Xbox 360 game's achievements read entirely from the Xenia
 * profile GPDs that X360 Mobile and XenDroid keep (see [X360AchievementDiscovery]). Fully offline,
 * like VITA_TROPHY and PS3_TROPHY, so a fetch never reports MissingCredentials.
 *
 * The provider game id is the 8-hex-digit title ID. Tiers come from gamerscore
 * ([ShibaTier.forGamerscore]); Xbox has no platinum, so the 100% crown is the locally minted one,
 * as for Steam. Rarity has no source here, so every coin uses [SyncedCoin.RARITY_UNAVAILABLE].
 */
@Singleton
class X360AchievementSource @Inject constructor(
    private val discovery: X360AchievementDiscovery,
) : RemoteAchievementSource {

    override suspend fun fetch(providerGameId: String): ProviderSyncResult =
        when (val load = discovery.load(providerGameId)) {
            X360AchievementDiscovery.Load.NotConfigured ->
                ProviderSyncResult.Failed("no Xbox 360 data folder set — pick X360 Mobile's or XenDroid's folder in the library")
            X360AchievementDiscovery.Load.NoProfile ->
                ProviderSyncResult.Failed("the Xbox 360 data folder has no emulator profile yet")
            // Xenia writes a title's GPD the first time it boots: "play it once", not "no achievements".
            X360AchievementDiscovery.Load.NotPlayed ->
                ProviderSyncResult.Failed("play $providerGameId once in X360 Mobile or XenDroid to start tracking its achievements")
            is X360AchievementDiscovery.Load.Found -> {
                if (load.achievements.isEmpty()) {
                    ProviderSyncResult.NotFound
                } else {
                    ProviderSyncResult.Success(providerGameId, load.achievements.map(::coinOf))
                }
            }
        }

    private fun coinOf(a: X360AchievementDiscovery.Achievement) = SyncedCoin(
        providerAchievementId = a.id.toString(),
        title = a.title,
        // Earned shows what was done; locked shows the hint, falling back to whichever text exists.
        description = (if (a.unlocked) a.unlockedDescription else a.lockedDescription)
            .ifBlank { a.unlockedDescription.ifBlank { a.lockedDescription } },
        tier = ShibaTier.forGamerscore(a.gamerscore),
        globalRarity = SyncedCoin.RARITY_UNAVAILABLE,
        iconUrl = a.iconUri,
        isHidden = a.secret,
        isEarned = a.unlocked,
        // Xbox has no hardcore/softcore split — mastery mirrors the unlock, like Vita and PS3.
        earnedHardcore = a.unlocked,
        earnedAt = a.unlockedAtEpochMillis,
    )
}
